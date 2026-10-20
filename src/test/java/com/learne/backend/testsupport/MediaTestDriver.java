package com.learne.backend.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.JobStore;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.audio.service.*;
import java.time.Duration;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit worker tick; the delete port can only reach the fixture's mocked storage probe. */
public class MediaTestDriver {
    private final JobStore jobs;
    private final MediaCleanupProducer producer;
    private final MediaCleanupHandler handler;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    public MediaTestDriver(JobStore jobs, MediaCleanupProducer producer, LessonAudioCleanupDecisionService decisions,
            StorageService storage, tools.jackson.databind.ObjectMapper mapper, PlatformTransactionManager manager, JdbcTemplate jdbc) {
        this.jobs=jobs; this.producer=producer; this.tx=new TransactionTemplate(manager); this.jdbc=jdbc;
        MediaDeletePort fake=id->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(org.mockito.Mockito.mockingDetails(storage).isMock()).as("fixture storage probe").isTrue();
            storage.delete(id,StorageResourceType.VIDEO);
        };
        handler=new MediaCleanupHandler(jobs,decisions,fake,mapper);
    }
    public void assertIntentAndDrain() {
        assertThat(jdbc.queryForObject("select count(*) from background_jobs where queue='MEDIA' and state='READY'",Integer.class)).isPositive();
        drain();
    }
    public void drain() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        for(int i=0;i<100;i++) {
            var lease=jobs.claim(QueueName.MEDIA,Map.of(JobType.MEDIA_CLEANUP,Set.of(1)),Duration.ofSeconds(30));
            if(lease.isEmpty()) return;
            try { handler.execute(lease.get()); }
            catch(com.learne.backend.jobs.service.RetryableJobException failure) {
                jobs.retry(lease.get(),java.time.Instant.now().plusSeconds(60),"TEST_RETRY");
            }
        }
        throw new AssertionError("MEDIA worker did not drain");
    }
    public void enqueueAndDrain(Long courseId,List<LessonAudioState> states) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) return;
        tx.executeWithoutResult(s->producer.enqueue(UUID.randomUUID(),courseId,states));
        assertIntentAndDrain();
    }
}
