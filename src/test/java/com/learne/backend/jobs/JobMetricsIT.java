package com.learne.backend.jobs;

import com.learne.backend.jobs.config.JobProperties;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class JobMetricsIT extends JobsPostgresTestSupport {
    @org.springframework.beans.factory.annotation.Autowired io.micrometer.core.instrument.MeterRegistry applicationMeters;

    @Test void realStoreCompletionRollbackCommitRetryAndStaleAttempt() {
        var id = UUID.randomUUID();
        new TransactionTemplate(manager).executeWithoutResult(s -> enqueuer.enqueue(new JobSpec(id,
                QueueName.MEDIA, JobType.MEDIA_CLEANUP, 1, 0, id.toString(), "{}", null, null)));
        var supported = Map.of(JobType.MEDIA_CLEANUP, Set.of(1));
        var lease = store.claim(QueueName.MEDIA, supported, Duration.ofSeconds(30)).orElseThrow();
        double before = completed();
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            assertThat(store.complete(lease, JobState.SUCCEEDED, null)).isTrue();
            assertThat(completed()).isEqualTo(before);
            s.setRollbackOnly();
        });
        assertThat(completed()).isEqualTo(before);
        assertThat(store.isLeaseCurrent(lease)).isTrue();
        assertThat(store.retry(lease, java.time.Instant.now().minusSeconds(1), "BUSY")).isTrue();
        assertThat(applicationMeters.get("learne.jobs.retries").tags("type","MEDIA_CLEANUP").counter().count()).isGreaterThanOrEqualTo(1);
        var next = store.claim(QueueName.MEDIA, supported, Duration.ofSeconds(30)).orElseThrow();
        assertThat(store.heartbeat(lease, Duration.ofSeconds(30))).isFalse();
        assertThat(applicationMeters.get("learne.jobs.lease.stale").tags("type","MEDIA_CLEANUP").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(store.complete(next, JobState.SUCCEEDED, null)).isTrue();
        assertThat(completed()).isEqualTo(before + 1);
    }

    private double completed() {
        var counter = applicationMeters.find("learne.jobs.completed").tags("type","MEDIA_CLEANUP","outcome","SUCCEEDED").counter();
        return counter == null ? 0 : counter.count();
    }
    @Test void nativeSnapshotUsesDueDatabaseTimeAndNeverClaimsUnsupportedVersions() {
        var meters = new SimpleMeterRegistry();
        var handler = mock(JobHandler.class);
        when(handler.type()).thenReturn(JobType.MEDIA_CLEANUP);
        when(handler.payloadVersion()).thenReturn(1);
        var handlers = new JobRegistry(List.of(handler));
        try (var metrics = new JobMetrics(meters, jdbc, handlers, new JobProperties().snapshot())) {
            metrics.refresh();
            assertThat(meters.get("learne.jobs.backlog").gauges()).allSatisfy(g -> assertThat(g.value()).isZero());
            for (int i = 0; i < 4; i++) {
                var id = UUID.randomUUID();
                new TransactionTemplate(manager).executeWithoutResult(s -> enqueuer.enqueue(new JobSpec(id,
                        QueueName.MEDIA, JobType.MEDIA_CLEANUP, 1, 0, id.toString(), "{}", null, null)));
            }
            jdbc.update("update background_jobs set available_at=clock_timestamp()-interval '120 seconds'");
            jdbc.update("update background_jobs set available_at=clock_timestamp()+interval '1 hour' where id=(select id from background_jobs limit 1)");
            jdbc.update("update background_jobs set payload_version=99 where id=(select id from background_jobs where available_at<clock_timestamp() limit 1)");
            jdbc.update("update background_jobs set type='PRIVATE_UNKNOWN_TYPE' where id=(select id from background_jobs where payload_version=1 and available_at<clock_timestamp() limit 1)");
            metrics.refresh();
            assertThat(meters.get("learne.jobs.backlog").tags("queue","MEDIA","type","MEDIA_CLEANUP","state","PENDING").gauge().value()).isEqualTo(3);
            assertThat(meters.get("learne.jobs.oldest.ready.seconds").tags("queue","MEDIA","type","MEDIA_CLEANUP").gauge().value()).isBetween(120.0, 140.0);
            assertThat(meters.get("learne.jobs.unsupported.version").counters()).allSatisfy(c -> assertThat(c.getId().getTag("outcome")).isEqualTo("UNSUPPORTED"));
            assertThat(meters.get("learne.jobs.unsupported.version").counters().stream().mapToDouble(c -> c.count()).sum()).isEqualTo(2);
            var lease = store.claim(QueueName.MEDIA, handlers.supported(QueueName.MEDIA), Duration.ofSeconds(30)).orElseThrow();
            assertThat(store.claim(QueueName.MEDIA, handlers.supported(QueueName.MEDIA), Duration.ofSeconds(30))).isEmpty();
            double before = meters.get("learne.jobs.backlog").tags("queue","MEDIA","type","MEDIA_CLEANUP","state","PENDING").gauge().value();
            jdbc.execute("alter table background_jobs rename to hidden_jobs");
            try { assertThatCode(metrics::refresh).doesNotThrowAnyException(); }
            finally { jdbc.execute("alter table hidden_jobs rename to background_jobs"); }
            assertThat(meters.get("learne.jobs.backlog").tags("queue","MEDIA","type","MEDIA_CLEANUP","state","PENDING").gauge().value()).isEqualTo(before);
            jdbc.update("update background_jobs set state='RETRY_WAIT' where available_at>clock_timestamp()");
            metrics.refresh();
            assertThat(meters.get("learne.jobs.backlog").tags("queue","MEDIA","type","MEDIA_CLEANUP","state","RETRY").gauge().value()).isOne();
            assertThat(meters.get("learne.jobs.backlog").tags("queue","MEDIA","type","MEDIA_CLEANUP","state","RUNNING").gauge().value()).isOne();
            assertThat(store.complete(lease, JobState.DEAD, "HANDLER_ERROR")).isTrue();
            metrics.refresh();
            assertThat(meters.get("learne.jobs.backlog").tags("queue","MEDIA","type","MEDIA_CLEANUP","state","DEAD").gauge().value()).isOne();
            jdbc.update("delete from background_jobs where available_at<=clock_timestamp()");
            metrics.refresh();
            assertThat(meters.get("learne.jobs.oldest.ready.seconds").gauges()).allSatisfy(g -> assertThat(g.value()).isZero());
        }
    }
}
