package com.learne.backend.jobs;

import com.learne.backend.jobs.config.JobProperties;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobMetricsTests {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JobMetrics metrics = new JobMetrics(registry, jdbc, new JobRegistry(List.of()), new JobProperties().snapshot());
    private JobLease lease() {
        return new JobLease(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), JobType.MEDIA_CLEANUP,
                1, 1, 5, "{}", null, false);
    }

    @Test void twoJobIdsShareOneOutcomeSeriesAndOnlyFiniteTags() {
        metrics.completed(lease(), JobState.SUCCEEDED);
        metrics.completed(lease(), JobState.SUCCEEDED);
        assertThat(registry.get("learne.jobs.completed").counters()).hasSize(1);
        assertThat(registry.get("learne.jobs.completed").counter().count()).isEqualTo(2);
        registry.getMeters().forEach(m -> assertThat(m.getId().getTags()).allSatisfy(t ->
                assertThat(t.getKey()).isIn("queue", "type", "state", "outcome")));
    }

    @Test void completionWaitsForCommitAndRollbackDoesNotCount() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            metrics.completed(lease(), JobState.SUCCEEDED);
            assertThat(registry.find("learne.jobs.completed").counter()).isNull();
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(c -> c.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(registry.find("learne.jobs.completed").counter()).isNull();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            metrics.completed(lease(), JobState.SUCCEEDED);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(registry.get("learne.jobs.completed").counter().count()).isOne();
    }

    @Test void staleAttemptsAndTurnDurationAreRecorded() {
        var lease = lease();
        metrics.stale(lease);
        metrics.duration(lease, System.nanoTime() - 1000);
        assertThat(registry.get("learne.jobs.lease.stale").counter().count()).isOne();
        assertThat(registry.get("learne.jobs.duration").timer().count()).isOne();
    }

    @Test void constructionAndGaugeReadsNeverQueryDatabaseAndReadFailureIsSafe() {
        verifyNoInteractions(jdbc);
        assertThat(registry.get("learne.jobs.oldest.ready.seconds").gauges()).allSatisfy(g -> assertThat(g.value()).isZero());
        assertThatCode(metrics::refresh).doesNotThrowAnyException();
    }

    @Test void throwingInstrumentationCannotEscapeEvenFromAfterCommit() {
        var registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new io.micrometer.core.instrument.config.MeterFilter() {
            @Override public io.micrometer.core.instrument.Meter.Id map(io.micrometer.core.instrument.Meter.Id id) {
                throw new IllegalStateException("private instrumentation diagnostic");
            }
        });
        var metrics = new JobMetrics(registry, jdbc, new JobRegistry(List.of()), new JobProperties().snapshot());
        assertThatCode(() -> {
            metrics.completed(lease(), JobState.DEAD);
            metrics.retried(lease());
            metrics.stale(lease());
            metrics.duration(lease(), System.nanoTime());
        }).doesNotThrowAnyException();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            metrics.completed(lease(), JobState.SUCCEEDED);
            assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
