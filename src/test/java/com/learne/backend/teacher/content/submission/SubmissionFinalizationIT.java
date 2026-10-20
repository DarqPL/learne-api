package com.learne.backend.teacher.content.submission;

import com.learne.backend.jobs.JobsPostgresTestSupport;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.context.TestPropertySource(properties="app.jobs.course-submission-enabled=true")
class SubmissionFinalizationIT extends JobsPostgresTestSupport {
    @Autowired SubmissionAcceptanceService acceptance;
    @Autowired SubmissionCompletionPort completion;
    @Autowired SubmissionCheckpointService checkpoints;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean SubmissionFinalizationService finalizerSpy;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean SubmissionCheckpointService checkpointSpy;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.learne.backend.teacher.content.enrichment.CourseKnowledgePointClient providerSpy;
    @Autowired com.learne.backend.teacher.content.enrichment.QuestionDifficultyCalculator difficulty;
    @Autowired com.learne.backend.teacher.content.enrichment.QuestionXpPolicy xp;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @Autowired com.learne.backend.jobs.service.JobRegistry registry;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.learne.backend.cache.CacheInvalidationService cache;
    private UUID fixture(int count) {
        long teacher=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('final@test.test','test','Teacher','TEACHER') returning id",Long.class);
        long course=jdbc.queryForObject("insert into courses(teacher_id,name,type,level,status) values (?,'Course','COMPREHENSIVE','A1','DRAFT') returning id",Long.class,teacher);
        long revision=jdbc.queryForObject("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,1,'DRAFT','Course','COMPREHENSIVE','A1') returning id",Long.class,course,teacher);
        long topic=jdbc.queryForObject("insert into topic_revisions(revision_id,name) values (?,'Topic') returning id",Long.class,revision);
        long lesson=jdbc.queryForObject("insert into lesson_revisions(topic_revision_id,teacher_id,title,skill_type,content) values (?,?,'Lesson','READING','Content') returning id",Long.class,topic,teacher);
        jdbc.update("insert into question_revisions(lesson_revision_id,teacher_id,type,question_text,order_index) select ?,?,'MULTIPLE_CHOICE','Question '||n,n from generate_series(1,?) n",lesson,teacher,count);
        UUID id=acceptance.accept(teacher,course).id();
        jdbc.update("update course_submission_questions set knowledge_point='present_simple',difficulty='easy',xp_reward=10");
        jdbc.update("update course_submission_attempts set state='PROCESSING',processed_questions=total_questions");
        return id;
    }
    private JobLease claim() { return store.claim(QueueName.AI,Map.of(JobType.COURSE_ENRICHMENT,Set.of(1)),Duration.ofSeconds(30)).orElseThrow(); }
    @Test void appliesThousandRowsAndTerminalizesWithoutAdmins() {
        var id=fixture(1000); completion.complete(claim(),id);
        assertThat(jdbc.queryForObject("select count(*) from question_revisions where knowledge_point='present_simple' and difficulty='easy' and xp_reward=10",Integer.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("select state from course_submission_attempts",String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select status from course_revisions",String.class)).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from user_notifications",Integer.class)).isEqualTo(1);
    }
    @Test void changedFingerprintFailsWithoutApplyingMetadata() {
        var id=fixture(2); jdbc.update("update question_revisions set question_text='changed'");
        completion.complete(claim(),id);
        assertFailed();
    }
    @Test void lockedTeacherFailsAndUnlocks() {
        var id=fixture(2); jdbc.update("update users set is_locked=true"); completion.complete(claim(),id); assertFailed();
    }
    @Test void exhaustedFailureLeavesContentUntouched() {
        var id=fixture(2); completion.fail(claim(),id,"EXHAUSTED_ATTEMPTS"); assertFailed();
    }
    @Test void staleLeaseCannotMutate() {
        var id=fixture(2); var lease=claim(); jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second'");
        assertThatThrownBy(() -> completion.complete(lease,id)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select state from course_submission_attempts",String.class)).isEqualTo("PROCESSING");
    }
    private void assertFailed() {
        assertThat(jdbc.queryForObject("select state from course_submission_attempts",String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select state from background_jobs where type='COURSE_ENRICHMENT'",String.class)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select count(*) from question_revisions where knowledge_point is not null",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_notifications",Integer.class)).isEqualTo(1);
    }
    @Test void realHandlerCompletesThousandAfterFortyStagingTurns() {
        assertThat(registry.find(JobType.COURSE_ENRICHMENT,1)).isPresent();
        var id=fixture(1000);
        jdbc.update("update course_submission_questions set knowledge_point=null,difficulty=null,xp_reward=null");
        jdbc.update("update course_submission_attempts set processed_questions=0,state='QUEUED'");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var worker=new CourseSubmissionHandler(checkpoints,new com.learne.backend.teacher.content.enrichment.CourseReviewEnrichmentService((request,contract) -> {
            calls.incrementAndGet();
            return new com.learne.backend.teacher.content.enrichment.CourseKnowledgePointResponse(request.questions().stream().map(q ->
                    new com.learne.backend.teacher.content.enrichment.CourseKnowledgePointResponse.Item(q.questionId(),"Present Simple")).toList());
        },difficulty,xp),completion,mapper);
        for(int i=0;i<40;i++) worker.execute(claim());
        assertThat(jdbc.queryForObject("select count(*) from question_revisions where knowledge_point is not null",Integer.class)).isZero();
        worker.execute(claim()); assertThat(calls).hasValue(40);
        assertThat(jdbc.queryForObject("select state from course_submission_attempts",String.class)).isEqualTo("SUCCEEDED");
    }
    @Test void allActiveAdminsIncludingLockedGetIndependentMail() {
        var id=fixture(2);
        jdbc.update("insert into users(email,password_hash,full_name,role,is_active,is_locked) values ('a@test.test','t','Admin','ADMIN',true,false),('b@test.test','t','Locked','ADMIN',true,true),('c@test.test','t','Inactive','ADMIN',false,false),('s@test.test','t','Student','STUDENT',true,false)");
        completion.complete(claim(),id);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs where type='COURSE_PENDING_REVIEW_MAIL' and dedupe_key like ?",Integer.class,"course-pending:"+id+":%")).isEqualTo(2);
    }
    @Test void missingRevisionFailsHistorically() {
        var id=fixture(2); jdbc.update("delete from question_revisions"); jdbc.update("delete from lesson_revisions"); jdbc.update("delete from topic_revisions"); jdbc.update("delete from course_revisions");
        completion.complete(claim(),id); assertFailed();
        assertThat(jdbc.queryForObject("select failure_code from course_submission_attempts",String.class)).isEqualTo("SUBMISSION_RESOURCE_UNAVAILABLE");
    }
    @Test void missingCourseFailsHistorically() {
        var id=fixture(2); jdbc.update("delete from question_revisions"); jdbc.update("delete from lesson_revisions"); jdbc.update("delete from topic_revisions"); jdbc.update("delete from course_revisions"); jdbc.update("delete from courses");
        completion.fail(claim(),id,"EXHAUSTED_ATTEMPTS"); assertFailed();
    }
    @Test void sqlFailureInLaterBatchRollsBackEarlierBatches() {
        var id=fixture(250); var lease=claim();
        jdbc.execute("create function c7_reject() returns trigger language plpgsql as $$ begin if NEW.order_index=150 then raise exception 'injected'; end if; return NEW; end $$");
        jdbc.execute("create trigger c7_reject before update on question_revisions for each row execute function c7_reject()");
        try { assertThatThrownBy(() -> completion.complete(lease,id)).isInstanceOf(RuntimeException.class); assertRolledBack(); }
        finally { jdbc.execute("drop trigger c7_reject on question_revisions"); jdbc.execute("drop function c7_reject()"); }
    }
    @Test void mailEnqueueFailureRollsBackMetadataAndTerminalState() {
        var id=fixture(2); var lease=claim();
        jdbc.update("insert into users(email,password_hash,full_name,role) values ('a@test.test','t','Admin','ADMIN')");
        jdbc.execute("create function c7_mail_reject() returns trigger language plpgsql as $$ begin if NEW.type='COURSE_PENDING_REVIEW_MAIL' then raise exception 'injected'; end if; return NEW; end $$");
        jdbc.execute("create trigger c7_mail_reject before insert on background_jobs for each row execute function c7_mail_reject()");
        try { assertThatThrownBy(() -> completion.complete(lease,id)).isInstanceOf(com.learne.backend.jobs.service.JobEnqueueException.class); assertRolledBack(); }
        finally { jdbc.execute("drop trigger c7_mail_reject on background_jobs"); jdbc.execute("drop function c7_mail_reject()"); }
    }
    private void assertRolledBack() {
        assertThat(jdbc.queryForObject("select count(*) from question_revisions where knowledge_point is not null",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select state from course_submission_attempts",String.class)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("select state from background_jobs where type='COURSE_ENRICHMENT'",String.class)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select status from course_revisions",String.class)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select count(*) from user_notifications",Integer.class)).isZero();
    }
    @Test void eachCacheFailureAfterCommitCannotUndoSuccess() {
        var id=fixture(2);
        org.mockito.Mockito.doThrow(new IllegalStateException("cache offline")).when(cache).evictTeacherDashboard(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.doThrow(new IllegalStateException("cache offline")).when(cache).evictGlobalCourseCatalog();
        org.mockito.Mockito.doThrow(new IllegalStateException("cache offline")).when(cache).evictAdminDashboard();
        completion.complete(claim(),id);
        assertThat(jdbc.queryForObject("select state from course_submission_attempts",String.class)).isEqualTo("SUCCEEDED");
        org.mockito.Mockito.verify(cache).evictGlobalCourseCatalog(); org.mockito.Mockito.verify(cache).evictAdminDashboard();
    }
    @Test void removedTeacherRoleFailsWithoutApplying() {
        var id=fixture(2); jdbc.update("update users set role='STUDENT'"); completion.complete(claim(),id); assertFailed();
    }
    @Test void incompleteStagedSetFailsWithoutApplyingEarlierRows() {
        var id=fixture(250); jdbc.update("update course_submission_questions set knowledge_point=null,difficulty=null,xp_reward=null where ordinal=249");
        completion.complete(claim(),id); assertFailed();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void incomingPayloadMustMatchSubmissionEvenWithCurrentToken(boolean success) {
        var id=fixture(2); var real=claim();
        var forged=new JobLease(real.jobId(),real.token(),real.leaseUntil(),real.type(),real.payloadVersion(),real.attempt(),real.maxAttempts(),
                "{\"submissionId\":\""+UUID.randomUUID()+"\"}",null,false);
        assertThatThrownBy(() -> finish(success,forged,id)).isInstanceOf(IllegalStateException.class);
        assertRolledBack();
    }
    private void finish(boolean success,JobLease lease,UUID id) { if(success) completion.complete(lease,id); else completion.fail(lease,id,"EXHAUSTED_ATTEMPTS"); }
    @Test void publishedCourseRemainsUnchangedAndOlderPendingIsSuperseded() {
        var id=fixture(2);
        var row=jdbc.queryForMap("select course_id,revision_id,teacher_id from course_submission_attempts");
        long course=((Number)row.get("course_id")).longValue(), revision=((Number)row.get("revision_id")).longValue();
        jdbc.update("update course_revisions set revision_number=3 where id=?",revision);
        jdbc.update("update course_submission_attempts set revision_number=3");
        // Capture the new immutable identity before testing the transition.
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        var projections=new SubmissionProjectionReader(jdbc);
        var fingerprint=tx.execute(s -> projections.capture(course,com.learne.backend.teacher.content.enrichment.EnrichmentContract.V1).globalFingerprint());
        jdbc.update("update course_submission_attempts set fingerprint=?",fingerprint);
        long older=jdbc.queryForObject("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,2,'PENDING_REVIEW','Older','COMPREHENSIVE','A1') returning id",Long.class,course,row.get("teacher_id"));
        jdbc.update("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,1,'APPROVED','Live','COMPREHENSIVE','A1')",course,row.get("teacher_id"));
        jdbc.update("update courses set status='APPROVED',published_at=clock_timestamp(),name='Live name',description='Live description' where id=?",course);
        var live=jdbc.queryForMap("select * from courses where id=?",course);
        // Changing revision number changes per-question fingerprints as well.
        tx.executeWithoutResult(s -> { var capture=projections.capture(course,com.learne.backend.teacher.content.enrichment.EnrichmentContract.V1);
            for(var entry:capture.manifestEntries()) jdbc.update("update course_submission_questions set input_fingerprint=? where question_revision_id=?",entry.perQuestionInputFingerprint(),entry.questionRevisionId()); });
        completion.complete(claim(),id);
        assertThat(jdbc.queryForMap("select * from courses where id=?",course)).isEqualTo(live);
        assertThat(jdbc.queryForObject("select status from course_revisions where id=?",String.class,older)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("select status from course_revisions where id=?",String.class,revision)).isEqualTo("PENDING_REVIEW");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,token","false,token","true,job","false,job","true,version","false,version","true,type","false,type","true,storedVersion","false,storedVersion","true,storedPayload","false,storedPayload","true,extraPayload","false,extraPayload","true,terminal","false,terminal","true,expired","false,expired","true,storedType","false,storedType","true,storedExtraPayload","false,storedExtraPayload"})
    void leaseMismatchNeverMutatesDomain(boolean success,String mismatch) {
        var id=fixture(2); var lease=claim();
        var job=lease.jobId(); var token=lease.token(); var type=lease.type(); int version=lease.payloadVersion(); String payload=lease.payloadJson();
        switch(mismatch) {
            case "token" -> token=UUID.randomUUID();
            case "job" -> job=UUID.randomUUID();
            case "version" -> version=2;
            case "type" -> type=JobType.COURSE_PENDING_REVIEW_MAIL;
            case "storedVersion" -> jdbc.update("update background_jobs set payload_version=2");
            case "storedPayload" -> jdbc.update("update background_jobs set payload=jsonb_build_object('submissionId',?::text)",UUID.randomUUID().toString());
            case "extraPayload" -> payload="{\"submissionId\":\""+id+"\",\"extra\":1}";
            case "terminal" -> jdbc.update("update course_submission_attempts set state='FAILED',completed_at=clock_timestamp()");
            case "expired" -> jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second'");
            case "storedType" -> jdbc.update("update background_jobs set type='COURSE_PENDING_REVIEW_MAIL',queue='MAIL_NOTICE'");
            case "storedExtraPayload" -> jdbc.update("update background_jobs set payload=payload||'{\"extra\":1}'::jsonb");
        }
        var invalid=new JobLease(job,token,lease.leaseUntil(),type,version,lease.attempt(),lease.maxAttempts(),payload,null,false);
        var before=jdbc.queryForList("select * from course_submission_attempts"); var jobs=jdbc.queryForList("select * from background_jobs");
        assertThatThrownBy(() -> finish(success,invalid,id)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForList("select * from course_submission_attempts")).isEqualTo(before);
        assertThat(jdbc.queryForList("select * from background_jobs")).isEqualTo(jobs);
        assertThat(jdbc.queryForObject("select count(*) from question_revisions where knowledge_point is not null",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_notifications",Integer.class)).isZero();
    }
    private final class ObservedHandler implements JobHandler {
        final JobHandler delegate=registry.find(JobType.COURSE_ENRICHMENT,1).orElseThrow();
        final AtomicInteger executions=new AtomicInteger(),failures=new AtomicInteger();
        final AtomicReference<RuntimeException> error=new AtomicReference<>();
        final CountDownLatch exited=new CountDownLatch(1);
        public JobType type() { return delegate.type(); }
        public int payloadVersion() { return 1; }
        public void execute(JobLease lease) { executions.incrementAndGet(); try { delegate.execute(lease); }
            catch(RuntimeException e) { assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); error.set(e); throw e; } finally { exited.countDown(); } }
        public void failPermanently(JobLease lease,String code) { failures.incrementAndGet(); try { delegate.failPermanently(lease,code); }
            catch(RuntimeException e) { assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); error.set(e); throw e; } finally { exited.countDown(); } }
    }
    private JobDispatcher dispatcher(ObservedHandler handler) {
        var properties=new com.learne.backend.jobs.config.JobProperties(); properties.setWorkerEnabled(false);
        return new JobDispatcher(store,new JobRegistry(List.of(handler)),properties.snapshot(),java.time.Clock.systemUTC(),() -> 0);
    }
    private void awaitState(String state) {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!state.equals(jdbc.queryForObject("select state from background_jobs where type='COURSE_ENRICHMENT'",String.class)) && System.nanoTime()<until)
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        assertThat(jdbc.queryForObject("select state from background_jobs where type='COURSE_ENRICHMENT'",String.class)).isEqualTo(state);
    }
    @Test void realDispatcherRecoversExpiredExhaustedLeaseAndUnlocks() throws Exception {
        fixture(2); var abandoned=claim();
        jdbc.update("update background_jobs set attempts=max_attempts,lease_until=clock_timestamp()-interval '1 second'");
        assertThat(store.recoverExpired(10)).isEqualTo(1);
        var handler=new ObservedHandler();
        try(var dispatcher=dispatcher(handler)) {
            assertThat(dispatcher.dispatch(QueueName.AI)).isTrue(); assertThat(handler.exited.await(5,TimeUnit.SECONDS)).isTrue(); awaitState("DEAD");
        }
        assertThat(handler.executions).hasValue(0); assertThat(handler.failures).hasValue(1); assertThat(handler.error).hasNullValue();
        assertFailed(); assertThat(jdbc.queryForObject("select failure_code from course_submission_attempts",String.class)).isEqualTo("EXHAUSTED_ATTEMPTS");
        assertThatThrownBy(() -> completion.complete(abandoned,jdbc.queryForObject("select id from course_submission_attempts",UUID.class))).isInstanceOf(IllegalStateException.class);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void postgres55P03RetriesAfterRollbackThenCompletes(boolean duringCheckpoint) throws Exception {
        var id=fixture(2);
        var raw=new AtomicReference<org.springframework.jdbc.UncategorizedSQLException>();
        org.mockito.stubbing.Answer<Object> lockedTurn=invocation->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.execute("set local lock_timeout='250ms'");
            assertThat(jdbc.queryForObject("show statement_timeout",String.class)).isEqualTo("2s");
            try { return invocation.callRealMethod(); }
            catch(org.springframework.jdbc.UncategorizedSQLException e) { raw.set(e); throw e; }
        };
        if(duringCheckpoint) {
            org.mockito.Mockito.doAnswer(invocation->{
                var request=(com.learne.backend.teacher.content.enrichment.CourseKnowledgePointRequest)invocation.getArgument(0);
                return new com.learne.backend.teacher.content.enrichment.CourseKnowledgePointResponse(request.questions().stream().map(q->new com.learne.backend.teacher.content.enrichment.CourseKnowledgePointResponse.Item(q.questionId(),"point")).toList());
            }).when(providerSpy).enrichKnowledgePoints(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            jdbc.update("update course_submission_questions set knowledge_point=null,difficulty=null,xp_reward=null");
            jdbc.update("update course_submission_attempts set state='QUEUED',processed_questions=0");
            org.mockito.Mockito.doAnswer(lockedTurn).when(checkpointSpy).checkpoint(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        } else org.mockito.Mockito.doAnswer(lockedTurn).when(finalizerSpy).complete(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var observed=new ObservedHandler();
        try(var holder=dataSource.getConnection();var dispatcher=dispatcher(observed)) {
            holder.setAutoCommit(false);
            try(var statement=holder.prepareStatement("select id from courses for update")) { statement.executeQuery().close(); }
            assertThat(dispatcher.dispatch(QueueName.AI)).isTrue();
            assertThat(observed.exited.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(raw.get()).isNotNull(); assertThat(raw.get().getSQLException().getSQLState()).isEqualTo("55P03");
            assertThat(observed.error.get()).isInstanceOf(RetryableJobException.class);
            awaitState("RETRY_WAIT"); assertThat(observed.failures).hasValue(0);
            assertThat(jdbc.queryForObject("select attempts from background_jobs",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from user_notifications",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from question_revisions where knowledge_point is not null",Integer.class)).isZero();
            if(duringCheckpoint) {
                assertThat(jdbc.queryForObject("select processed_questions from course_submission_attempts",Integer.class)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from course_submission_questions where knowledge_point is not null",Integer.class)).isZero();
            }
            holder.rollback();
        }
        jdbc.update("update background_jobs set available_at=clock_timestamp()");
        var resumed=new ObservedHandler(); try(var dispatcher=dispatcher(resumed)) {
            assertThat(dispatcher.dispatch(QueueName.AI)).isTrue(); assertThat(resumed.exited.await(5,TimeUnit.SECONDS)).isTrue();
            awaitState(duringCheckpoint?"READY":"SUCCEEDED"); assertThat(resumed.failures).hasValue(0);
        }
        if(duringCheckpoint) {
            var finish=new ObservedHandler(); try(var dispatcher=dispatcher(finish)) {
                assertThat(dispatcher.dispatch(QueueName.AI)).isTrue(); assertThat(finish.exited.await(5,TimeUnit.SECONDS)).isTrue(); awaitState("SUCCEEDED");
            }
        }
        assertThat(jdbc.queryForObject("select state from course_submission_attempts where id=?",String.class,id)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select status from course_revisions",String.class)).isEqualTo("PENDING_REVIEW");
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void finalizerLockTimeoutRollsBackAndDispatcherSafelyRetriesOrRecovers(boolean controlOnly) throws Exception {
        fixture(2); if(controlOnly) jdbc.update("update background_jobs set attempts=max_attempts");
        var handler=new ObservedHandler();
        try(var lock=dataSource.getConnection(); var dispatcher=dispatcher(handler)) {
            lock.setAutoCommit(false); try(var statement=lock.prepareStatement("select id from courses for update")) { statement.executeQuery().close(); }
            assertThat(dispatcher.dispatch(QueueName.AI)).isTrue(); assertThat(handler.exited.await(12,TimeUnit.SECONDS)).isTrue();
            assertThat(handler.error.get()).isInstanceOf(RetryableJobException.class);
            if(controlOnly) { awaitState("RUNNING"); assertRolledBack(); }
            else { awaitState("RETRY_WAIT"); assertThat(handler.failures).hasValue(0); }
            assertThat(jdbc.queryForObject("select state from course_submission_attempts",String.class)).isEqualTo("PROCESSING");
            assertThat(jdbc.queryForObject("select count(*) from user_notifications",Integer.class)).isZero();
            lock.rollback();
        }
        if(controlOnly) { jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second'"); assertThat(store.recoverExpired(10)).isEqualTo(1); }
        else jdbc.update("update background_jobs set available_at=clock_timestamp()");
        var resumed=new ObservedHandler(); try(var dispatcher=dispatcher(resumed)) { assertThat(dispatcher.dispatch(QueueName.AI)).isTrue(); assertThat(resumed.exited.await(5,TimeUnit.SECONDS)).isTrue(); awaitState(controlOnly?"DEAD":"SUCCEEDED"); }
        if(controlOnly) assertFailed(); else assertThat(jdbc.queryForObject("select state from course_submission_attempts",String.class)).isEqualTo("SUCCEEDED");
    }
    @Test void repeatedTransientFinalizationUsesFiniteAttemptsThenRealFailureUnlocks() throws Exception {
        fixture(2);
        jdbc.execute("create function c7_transient() returns trigger language plpgsql as $$ begin raise exception 'injected' using errcode='40001'; end $$");
        jdbc.execute("create trigger c7_transient before update on question_revisions for each row execute function c7_transient()");
        try {
            int max=jdbc.queryForObject("select max_attempts from background_jobs",Integer.class);
            for(int attempt=1;attempt<=max;attempt++) {
                var handler=new ObservedHandler(); try(var dispatcher=dispatcher(handler)) {
                    assertThat(dispatcher.dispatch(QueueName.AI)).isTrue(); assertThat(handler.exited.await(5,TimeUnit.SECONDS)).isTrue(); awaitState(attempt==max?"DEAD":"RETRY_WAIT");
                }
                assertThat(handler.error.get()).isInstanceOf(RetryableJobException.class);
                assertThat(jdbc.queryForObject("select attempts from background_jobs",Integer.class)).isEqualTo(attempt);
                assertThat(jdbc.queryForObject("select count(*) from question_revisions where knowledge_point is not null",Integer.class)).isZero();
                if(attempt<max) {
                    assertThat(handler.failures).hasValue(0);
                    assertThat(jdbc.queryForObject("select state from course_submission_attempts",String.class)).isEqualTo("PROCESSING");
                    jdbc.update("update background_jobs set available_at=clock_timestamp()");
                }
            }
            assertFailed(); assertThat(jdbc.queryForObject("select failure_code from course_submission_attempts",String.class)).isEqualTo("EXHAUSTED_ATTEMPTS");
        } finally { jdbc.execute("drop trigger c7_transient on question_revisions"); jdbc.execute("drop function c7_transient()"); }
    }
    @Test void failureNotificationInfrastructureRollsBackThenControlRecoveryUnlocks() throws Exception {
        fixture(2);
        // Fail before finalization, then fail the real permanent callback's notification insert.
        jdbc.update("update course_submission_attempts set policy_version='unavailable'");
        jdbc.execute("create function c7_notification_transient() returns trigger language plpgsql as $$ begin raise exception 'injected' using errcode='40001'; end $$");
        jdbc.execute("create trigger c7_notification_transient before insert on user_notifications for each row execute function c7_notification_transient()");
        var handler=new ObservedHandler();
        try {
            try(var dispatcher=dispatcher(handler)) {
                assertThat(dispatcher.dispatch(QueueName.AI)).isTrue();
                // The execution latch fires before the dispatcher invokes the failure callback.
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!(handler.error.get() instanceof RetryableJobException) && System.nanoTime()<until)
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                assertThat(handler.error.get()).isInstanceOf(RetryableJobException.class);
            }
            assertRolledBack(); assertThat(handler.failures).hasValue(1);
        } finally { jdbc.execute("drop trigger c7_notification_transient on user_notifications"); jdbc.execute("drop function c7_notification_transient()"); }
        jdbc.update("update background_jobs set attempts=max_attempts,lease_until=clock_timestamp()-interval '1 second'");
        assertThat(store.recoverExpired(10)).isEqualTo(1);
        var recovered=new ObservedHandler(); try(var dispatcher=dispatcher(recovered)) {
            assertThat(dispatcher.dispatch(QueueName.AI)).isTrue(); assertThat(recovered.exited.await(5,TimeUnit.SECONDS)).isTrue(); awaitState("DEAD");
        }
        assertThat(recovered.executions).hasValue(0); assertFailed();
    }
    @Test void mailEnqueueInfrastructureRetriesThroughRealDispatcherWithoutFailingDomain() throws Exception {
        fixture(2);
        jdbc.update("insert into users(email,password_hash,full_name,role) values ('infra@test.test','t','Admin','ADMIN')");
        jdbc.execute("create function c7_enqueue_transient() returns trigger language plpgsql as $$ begin if NEW.type='COURSE_PENDING_REVIEW_MAIL' then raise exception 'injected'; end if; return NEW; end $$");
        jdbc.execute("create trigger c7_enqueue_transient before insert on background_jobs for each row execute function c7_enqueue_transient()");
        var handler=new ObservedHandler(); try {
            try(var dispatcher=dispatcher(handler)) { assertThat(dispatcher.dispatch(QueueName.AI)).isTrue(); assertThat(handler.exited.await(5,TimeUnit.SECONDS)).isTrue(); awaitState("RETRY_WAIT"); }
            assertThat(handler.error.get()).isInstanceOf(RetryableJobException.class); assertThat(handler.failures).hasValue(0);
            assertThat(jdbc.queryForObject("select state from course_submission_attempts",String.class)).isEqualTo("PROCESSING");
            assertThat(jdbc.queryForObject("select count(*) from question_revisions where knowledge_point is not null",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from user_notifications",Integer.class)).isZero();
        } finally { jdbc.execute("drop trigger c7_enqueue_transient on background_jobs"); jdbc.execute("drop function c7_enqueue_transient()"); }
        jdbc.update("update background_jobs set available_at=clock_timestamp()");
        var resumed=new ObservedHandler(); try(var dispatcher=dispatcher(resumed)) { assertThat(dispatcher.dispatch(QueueName.AI)).isTrue(); assertThat(resumed.exited.await(5,TimeUnit.SECONDS)).isTrue(); awaitState("SUCCEEDED"); }
        assertThat(jdbc.queryForObject("select count(*) from background_jobs where type='COURSE_PENDING_REVIEW_MAIL'",Integer.class)).isEqualTo(1);
    }
}
