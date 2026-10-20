package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.jobs.JobsPostgresTestSupport;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.JobMaintenanceService;
import com.learne.backend.teacher.content.audio.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.learne.backend.teacher.content.audio.dto.*;
import com.learne.backend.jobs.service.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.TestPropertySource;

@org.junit.jupiter.api.Timeout(20)
@TestPropertySource(properties="app.cloudinary.cloud-name=test-cloud")
class MediaCleanupIT extends JobsPostgresTestSupport {
    @Autowired MediaCleanupProducer producer;
    @Autowired JobMaintenanceService maintenance;
    @Autowired LessonAudioCleanupDecisionService decisions;
    @Autowired com.learne.backend.storage.config.CloudinaryProperties cloud;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    long[] fixture() {
        cloud.setCloudName("test-cloud");
        long teacher=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('media@test.test','test','Teacher','TEACHER') returning id",Long.class);
        long course=jdbc.queryForObject("insert into courses(teacher_id,name,type,level,status) values (?,'Course','COMPREHENSIVE','A1','DRAFT') returning id",Long.class,teacher);
        long revision=jdbc.queryForObject("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,1,'DRAFT','Course','COMPREHENSIVE','A1') returning id",Long.class,course,teacher);
        long topic=jdbc.queryForObject("insert into topic_revisions(revision_id,name) values (?,'Topic') returning id",Long.class,revision);
        var state=LessonAudioCleanupServiceTests.state("a");
        long lesson=jdbc.queryForObject("insert into lesson_revisions(topic_revision_id,teacher_id,title,skill_type,audio_url,audio_public_id) values (?,?,'Lesson','READING',?,?) returning id",Long.class,topic,teacher,state.url(),state.publicId());
        return new long[]{course,lesson};
    }
    void enqueue(UUID event,long course) { new TransactionTemplate(manager).executeWithoutResult(s->producer.enqueue(event,course,List.of(LessonAudioCleanupServiceTests.state("a")))); }
    JobLease claim() { return store.claim(QueueName.MEDIA,Map.of(JobType.MEDIA_CLEANUP,Set.of(1)),Duration.ofSeconds(30)).orElseThrow(); }
    String state(UUID id) { return jdbc.queryForObject("select state from background_jobs where id=?",String.class,id); }
    @Test void leaseLostDuringDecisionPreventsFreshDeleteAndProviderRetryRechecksReferences() {
        var ids=fixture(); jdbc.update("update lesson_revisions set audio_url=null,audio_public_id=null where id=?",ids[1]);
        enqueue(UUID.randomUUID(),ids[0]); var lease=claim(); var deletes=new AtomicInteger();
        var expiringDecision=new LessonAudioCleanupDecisionService(null,null,null,null) {
            @Override public CleanupDecision evaluate(Long course,LessonAudioState candidate) {
                var result=decisions.evaluate(course,candidate);
                jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second' where id=?",lease.jobId());
                return result;
            }
        };
        new MediaCleanupHandler(store,expiringDecision,id->deletes.incrementAndGet(),mapper).execute(lease);
        assertThat(deletes).hasValue(0); assertThat(store.recoverExpired(10)).isOne();
        var retryLease=claim();
        var handler=new MediaCleanupHandler(store,decisions,id->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            deletes.incrementAndGet(); throw new RetryableJobException("BUSY",null);
        },mapper);
        assertThatThrownBy(()->handler.execute(retryLease)).isInstanceOf(RetryableJobException.class);
        assertThat(store.retry(retryLease,java.time.Instant.now(),"BUSY")).isTrue();
        var candidate=LessonAudioCleanupServiceTests.state("a");
        jdbc.update("update lesson_revisions set audio_url=?,audio_public_id=? where id=?",candidate.url(),candidate.publicId(),ids[1]);
        var fresh=claim(); handler.execute(fresh); assertThat(state(fresh.jobId())).isEqualTo("SKIPPED"); assertThat(deletes).hasValue(1);
    }
    @Test void realHistoricalReferenceRetainsThenNewRemovalEventDeletesDespitePriorRetainedAndDead() {
        var ids=fixture(); var event=UUID.randomUUID(); enqueue(event,ids[0]); enqueue(event,ids[0]);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isOne();
        var deletes=new AtomicInteger();
        var handler=new MediaCleanupHandler(store,decisions,id->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // The decision connection/lock has been released before provider I/O.
            new TransactionTemplate(manager).executeWithoutResult(s->jdbc.queryForObject("select id from courses where id=? for update nowait",Long.class,ids[0]));
            deletes.incrementAndGet();
        },mapper);
        var retained=claim(); handler.execute(retained); assertThat(state(retained.jobId())).isEqualTo("SKIPPED");
        assertThat(deletes).hasValue(0);
        enqueue(UUID.randomUUID(),ids[0]); var dead=claim(); handler.failPermanently(dead,"EXHAUSTED_ATTEMPTS");
        new TransactionTemplate(manager).executeWithoutResult(s->{
            jdbc.queryForObject("select id from courses where id=? for update",Long.class,ids[0]);
            jdbc.update("update lesson_revisions set audio_url=null,audio_public_id=null where id=?",ids[1]);
            producer.enqueue(UUID.randomUUID(),ids[0],List.of(LessonAudioCleanupServiceTests.state("a")));
        });
        handler.execute(claim()); assertThat(deletes).hasValue(1);
        assertThat(state(retained.jobId())).isEqualTo("SKIPPED"); assertThat(state(dead.jobId())).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select count(*) from background_jobs where state='SUCCEEDED'",Integer.class)).isOne();
    }
    @Test void databaseLockFailureRetriesThenFreshDecisionRetainsAndStaleLeaseCannotDelete() throws Exception {
        var ids=fixture(); enqueue(UUID.randomUUID(),ids[0]); var lease=claim(); var deletes=new AtomicInteger();
        var handler=new MediaCleanupHandler(store,decisions,id->deletes.incrementAndGet(),mapper);
        try(var locked=dataSource.getConnection()) {
            locked.setAutoCommit(false); try(var statement=locked.prepareStatement("select id from courses where id=? for update")) { statement.setLong(1,ids[0]); statement.executeQuery().close(); }
            assertThatThrownBy(()->handler.execute(lease)).isInstanceOf(RetryableJobException.class);
            locked.rollback();
        }
        assertThat(deletes).hasValue(0); assertThat(state(lease.jobId())).isEqualTo("RUNNING");
        handler.execute(lease); assertThat(state(lease.jobId())).isEqualTo("SKIPPED");
        jdbc.update("update lesson_revisions set audio_url=null,audio_public_id=null where id=?",ids[1]);
        enqueue(UUID.randomUUID(),ids[0]); var stale=claim();
        jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second' where id=?",stale.jobId());
        handler.execute(stale); assertThat(deletes).hasValue(0);
        assertThat(store.recoverExpired(10)).isOne(); handler.execute(claim()); assertThat(deletes).hasValue(1);
    }
    @Test void missingCourseUnprovenAndAmbiguousReferencesSkipAndAmbientTransactionRejects() {
        var ids=fixture(); var candidate=LessonAudioCleanupServiceTests.state("a");
        assertThat(decisions.evaluate(Long.MAX_VALUE,candidate).reason()).isEqualTo("MISSING_COURSE");
        assertThat(decisions.evaluate(ids[0],new LessonAudioState("https://example.test/a",candidate.publicId())).reason()).isEqualTo("UNPROVEN_PROVENANCE");
        jdbc.update("update lesson_revisions set audio_public_id=null,audio_url='https://example.test/%252F' where id=?",ids[1]);
        assertThat(decisions.evaluate(ids[0],candidate).reason()).isEqualTo("AMBIGUOUS_REFERENCE");
        enqueue(UUID.randomUUID(),ids[0]); var lease=claim(); var deletes=new AtomicInteger();
        var handler=new MediaCleanupHandler(store,decisions,id->deletes.incrementAndGet(),mapper);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(s->handler.execute(lease))).isInstanceOf(IllegalStateException.class);
        handler.execute(lease); assertThat(state(lease.jobId())).isEqualTo("SKIPPED"); assertThat(deletes).hasValue(0);
    }
    @Test void mandatoryTransactionAndEventDedupeSurviveRetainedAndDeadJobs() {
        var event=UUID.randomUUID(); var state=LessonAudioCleanupServiceTests.state("a");
        assertThatThrownBy(()->producer.enqueue(event,1L,List.of(state))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(s->{producer.enqueue(event,1L,List.of(state,state));producer.enqueue(event,1L,List.of(state));});
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isOne();
        jdbc.update("update background_jobs set state='SKIPPED',completed_at=clock_timestamp(),last_error_code='REFERENCED'");
        tx.executeWithoutResult(s->producer.enqueue(UUID.randomUUID(),1L,List.of(state)));
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isEqualTo(2);
        jdbc.update("update background_jobs set state='DEAD',completed_at=clock_timestamp()");
        tx.executeWithoutResult(s->producer.enqueue(UUID.randomUUID(),1L,List.of(state)));
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isEqualTo(3);
    }
    @Test void terminalReplayIdentityIsKeptInsideThirtyDaysAndPurgedOutsideBoundary() {
        var tx=new TransactionTemplate(manager); var state=LessonAudioCleanupServiceTests.state("a");
        tx.executeWithoutResult(s->{producer.enqueue(UUID.randomUUID(),1L,List.of(state));producer.enqueue(UUID.randomUUID(),1L,List.of(state));});
        var ids=jdbc.queryForList("select id from background_jobs order by id",UUID.class);
        jdbc.update("update background_jobs set state='SKIPPED',completed_at=clock_timestamp()-interval '29 days' where id=?",ids.getFirst());
        jdbc.update("update background_jobs set state='DEAD',completed_at=clock_timestamp()-interval '31 days' where id=?",ids.getLast());
        assertThat(maintenance.maintain().terminalDeleted()).isOne();
        assertThat(jdbc.queryForList("select id from background_jobs",UUID.class)).containsExactly(ids.getFirst());
    }
}
