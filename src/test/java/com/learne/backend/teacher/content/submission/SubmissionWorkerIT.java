package com.learne.backend.teacher.content.submission;

import com.learne.backend.jobs.JobsPostgresTestSupport;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.teacher.content.enrichment.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class SubmissionWorkerIT extends JobsPostgresTestSupport {
    @Autowired SubmissionAcceptanceService acceptance;
    @Autowired SubmissionCheckpointService checkpoints;
    @Autowired QuestionDifficultyCalculator difficulty;
    @Autowired QuestionXpPolicy xp;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @Autowired JobRegistry registry;
    @Autowired SubmissionCompletionPort completion;
    @Autowired SubmissionRepository submissions;
    private UUID fixture(int count, String email) {
        long teacher=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values (?,'test','Teacher','TEACHER') returning id",Long.class,email);
        long course=jdbc.queryForObject("insert into courses(teacher_id,name,type,level,status) values (?,'Course','COMPREHENSIVE','A1','DRAFT') returning id",Long.class,teacher);
        long revision=jdbc.queryForObject("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,1,'DRAFT','Course','COMPREHENSIVE','A1') returning id",Long.class,course,teacher);
        long topic=jdbc.queryForObject("insert into topic_revisions(revision_id,name) values (?,'Topic') returning id",Long.class,revision);
        long lesson=jdbc.queryForObject("insert into lesson_revisions(topic_revision_id,teacher_id,title,skill_type,content) values (?,?,'Lesson','READING','Content') returning id",Long.class,topic,teacher);
        jdbc.update("insert into question_revisions(lesson_revision_id,teacher_id,type,question_text,order_index) select ?,?,'MULTIPLE_CHOICE','Question '||n,n from generate_series(1,?) n",lesson,teacher,count);
        return acceptance.accept(teacher,course).id();
    }
    private JobLease claim() { return store.claim(QueueName.AI,Map.of(JobType.COURSE_ENRICHMENT,Set.of(1)),Duration.ofSeconds(30)).orElseThrow(); }
    private CourseSubmissionHandler handler(CourseKnowledgePointClient client) {
        return new CourseSubmissionHandler(checkpoints,new CourseReviewEnrichmentService(client,difficulty,xp),new SubmissionCompletionPort() {
            public void complete(JobLease lease, UUID id) { assertThat(store.complete(lease,JobState.SUCCEEDED,null)).isTrue(); }
            public void fail(JobLease lease, UUID id,String code) { assertThat(store.complete(lease,JobState.DEAD,code)).isTrue(); }
        },mapper);
    }
    private CourseKnowledgePointResponse response(CourseKnowledgePointRequest request) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(request.questions()).hasSizeBetween(1,25);
        return new CourseKnowledgePointResponse(request.questions().stream().map(q -> new CourseKnowledgePointResponse.Item(q.questionId(),"Present Simple")).toList());
    }
    @Test void thousandQuestionsUseFortyAtomicTurnsAndThenCompletionPort() {
        UUID id=fixture(1000,"worker@test.test"); var calls=new AtomicInteger();
        var handler=handler((request,contract) -> { assertThat(contract).isEqualTo(EnrichmentContract.V1); calls.incrementAndGet(); return response(request); });
        for(int i=0;i<40;i++) handler.execute(claim());
        assertThat(calls).hasValue(40);
        assertThat(jdbc.queryForObject("select processed_questions from course_submission_attempts where id=?",Integer.class,id)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("select attempts from background_jobs",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from question_revisions where knowledge_point is not null",Integer.class)).isZero();
        handler.execute(claim()); assertThat(calls).hasValue(40);
        assertThat(jdbc.queryForObject("select state from background_jobs",String.class)).isEqualTo("SUCCEEDED");
    }
    @Test
    @org.junit.jupiter.api.Timeout(90)
    void compatibleDeploymentAfterTwentyOfFortyBatchesResumesPinnedContractWithoutReplay() {
        UUID id=fixture(1000,"deploy@test.test");
        jdbc.update("""
                insert into users(email,password_hash,full_name,role,is_active,is_locked) values
                ('deploy-admin@test.test','test','Admin','ADMIN',true,false),
                ('deploy-locked@test.test','test','Locked admin','ADMIN',true,true),
                ('deploy-inactive@test.test','test','Inactive admin','ADMIN',false,false),
                ('deploy-student@test.test','test','Student','STUDENT',true,false)
                """);
        var identity=jdbc.queryForMap("""
                select id,course_id,revision_id,teacher_id,policy_version,prompt_version,response_version,
                       fingerprint,created_at from course_submission_attempts where id=?
                """,id);
        var manifest=jdbc.queryForList("select question_revision_id from course_submission_questions where submission_id=? order by ordinal",Long.class,id);
        var beforeCalls=new CopyOnWriteArrayList<Long>();
        var afterCalls=new CopyOnWriteArrayList<Long>();
        var batchesBefore=new AtomicInteger(); var batchesAfter=new AtomicInteger();
        var transport=org.mockito.Mockito.mock(EnrichmentHttpTransport.class);
        advertiseDeploymentContracts(transport,"1");
        var oldWorker=new CourseSubmissionHandler(checkpoints,new CourseReviewEnrichmentService((request,contract) -> {
            assertThat(contract).isEqualTo(EnrichmentContract.V1);
            batchesBefore.incrementAndGet();
            beforeCalls.addAll(request.questions().stream().map(q -> q.questionId()).toList());
            return response(request);
        },difficulty,xp),completion,mapper);
        try(var gate=deploymentGate(transport); var runtime=deploymentRuntime(oldWorker,gate)) {
            gate.refresh(); assertThat(gate.state()).isEqualTo(EnrichmentCapabilityGate.SafeState.READY);
            for(int batch=1;batch<=20;batch++) deploymentTurn(runtime,batch*25,"READY");
        } // Simulated deployment: retire old runtime/handler; retain the actual database and job.
        assertThat(batchesBefore).hasValue(20);
        assertThat(beforeCalls).containsExactlyElementsOf(manifest.subList(0,500));
        assertThat(submissions.activeContracts()).containsExactly(EnrichmentContract.V1);
        assertThat(submissions.parent(id).contract()).isEqualTo(EnrichmentContract.V1);
        var staged=jdbc.queryForList("select * from course_submission_questions where submission_id=? and ordinal<500 order by ordinal",id);
        assertThat(staged).hasSize(500).allSatisfy(row -> assertThat(row.get("knowledge_point")).isEqualTo("present_simple"));
        assertThat(jdbc.queryForObject("select count(*) from course_submission_questions where knowledge_point is not null",Integer.class)).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from question_revisions where knowledge_point is not null",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_notifications",Integer.class)).isZero();
        var midpointJob=jdbc.queryForMap("select * from background_jobs where type='COURSE_ENRICHMENT'");
        var midpointAttempt=jdbc.queryForMap("select * from course_submission_attempts where id=?",id);
        var newWorker=new CourseSubmissionHandler(checkpoints,new CourseReviewEnrichmentService((request,contract) -> {
            assertThat(contract).isEqualTo(EnrichmentContract.V1);
            var ids=request.questions().stream().map(q -> q.questionId()).toList();
            assertThat(ids).doesNotContainAnyElementsOf(beforeCalls);
            batchesAfter.incrementAndGet(); afterCalls.addAll(ids);
            return response(request);
        },difficulty,xp),completion,mapper);
        try(var gate=deploymentGate(transport); var runtime=deploymentRuntime(newWorker,gate)) {
            assertThat(runtime.dispatch(QueueName.AI)).isFalse(); // Fresh runtime starts paused.
            advertiseDeploymentContracts(transport,"2"); // New prompt alone cannot process pinned V1.
            gate.refresh();
            assertThat(gate.state()).isEqualTo(EnrichmentCapabilityGate.SafeState.UNSUPPORTED_TUPLE);
            assertThat(runtime.dispatch(QueueName.AI)).isFalse();
            assertThat(batchesAfter).hasValue(0);
            assertThat(jdbc.queryForMap("select * from background_jobs where type='COURSE_ENRICHMENT'")).isEqualTo(midpointJob);
            assertThat(jdbc.queryForMap("select * from course_submission_attempts where id=?",id)).isEqualTo(midpointAttempt);
            assertThat(submissions.activeContracts()).containsExactly(EnrichmentContract.V1);
            advertiseDeploymentContracts(transport,"1","2");
            gate.refresh(); assertThat(gate.state()).isEqualTo(EnrichmentCapabilityGate.SafeState.READY);
            for(int batch=21;batch<=40;batch++) deploymentTurn(runtime,batch*25,"READY");
            assertThat(jdbc.queryForObject("select count(*) from question_revisions where knowledge_point is not null",Integer.class)).isZero();
            deploymentTurn(runtime,1000,"SUCCEEDED"); // Real finalizer, no additional provider call.
            assertThat(runtime.dispatch(QueueName.AI)).isFalse();
        }
        assertThat(batchesBefore).hasValue(20); assertThat(batchesAfter).hasValue(20);
        assertThat(afterCalls).containsExactlyElementsOf(manifest.subList(500,1000));
        var allCalls=new ArrayList<>(beforeCalls); allCalls.addAll(afterCalls);
        assertThat(allCalls).hasSize(1000).doesNotHaveDuplicates().containsExactlyElementsOf(manifest);
        assertThat(jdbc.queryForList("select * from course_submission_questions where submission_id=? and ordinal<500 order by ordinal",id)).isEqualTo(staged);
        assertThat(jdbc.queryForMap("""
                select id,course_id,revision_id,teacher_id,policy_version,prompt_version,response_version,
                       fingerprint,created_at from course_submission_attempts where id=?
                """,id)).isEqualTo(identity);
        assertThat(submissions.parent(id).state()).isEqualTo("SUCCEEDED");
        assertThat(submissions.activeContracts()).isEmpty();
        assertThat(jdbc.queryForObject("select status from course_revisions where id=?",String.class,identity.get("revision_id"))).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("""
                select count(*) from question_revisions q join course_submission_questions s on s.question_revision_id=q.id
                where s.submission_id=? and q.knowledge_point=s.knowledge_point and q.difficulty=s.difficulty and q.xp_reward=s.xp_reward
                """,Integer.class,id)).isEqualTo(1000);
        assertThat(jdbc.queryForMap("select user_id,submission_id,course_id,revision_id,type from user_notifications"))
                .containsEntry("user_id",identity.get("teacher_id")).containsEntry("submission_id",id)
                .containsEntry("course_id",identity.get("course_id")).containsEntry("revision_id",identity.get("revision_id"))
                .containsEntry("type","COURSE_SUBMISSION_SUCCEEDED");
        var adminIds=jdbc.queryForList("select id from users where role='ADMIN' and is_active=true",Long.class);
        assertThat(adminIds).hasSize(2);
        assertThat(jdbc.queryForList("select dedupe_key from background_jobs where type='COURSE_PENDING_REVIEW_MAIL'",String.class))
                .containsExactlyInAnyOrderElementsOf(adminIds.stream().map(admin -> "course-pending:"+id+":"+admin).toList());
    }
    private void advertiseDeploymentContracts(EnrichmentHttpTransport transport,String... prompts) {
        String entries=Arrays.stream(prompts).map(prompt -> "{\"promptVersion\":\""+prompt+"\",\"responseVersion\":\"1\"}")
                .collect(java.util.stream.Collectors.joining(","));
        org.mockito.Mockito.when(transport.capabilities(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EnrichmentHttpTransport.Response(200,java.net.http.HttpHeaders.of(Map.of(),(a,b) -> true),
                        ("{\"supportedContracts\":["+entries+"]}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private EnrichmentCapabilityGate deploymentGate(EnrichmentHttpTransport transport) {
        return new EnrichmentCapabilityGate(new EnrichmentCapabilities(
                new com.learne.backend.student.learning.ai.AiServiceProperties("http://127.0.0.1:1",8000,true,"test"),transport),
                true,false,() -> List.of(submissions));
    }
    private JobDispatcher deploymentRuntime(CourseSubmissionHandler worker,EnrichmentCapabilityGate gate) {
        var properties=new com.learne.backend.jobs.config.JobProperties(); properties.setWorkerEnabled(false);
        return new JobDispatcher(store,new JobRegistry(List.of(worker)),properties.snapshot(),java.time.Clock.systemUTC(),() -> 0,null,List.of(gate));
    }
    private void deploymentTurn(JobDispatcher runtime,int progress,String jobState) {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        boolean dispatched=false;
        while(!(dispatched=runtime.dispatch(QueueName.AI)) && System.nanoTime()<until)
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        assertThat(dispatched).isTrue();
        while(System.nanoTime()<until) {
            if(jdbc.queryForObject("select processed_questions from course_submission_attempts",Integer.class)==progress
                    && jobState.equals(jdbc.queryForObject("select state from background_jobs where type='COURSE_ENRICHMENT'",String.class))) return;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        fail("Deployment turn did not commit progress %s and job state %s",progress,jobState);
    }
    @Test void duplicateResponseNeverCheckpoints() {
        fixture(25,"bad@test.test");
        var handler=handler((request,contract) -> new CourseKnowledgePointResponse(List.of(new CourseKnowledgePointResponse.Item(request.questions().getFirst().questionId(),"one"),new CourseKnowledgePointResponse.Item(request.questions().getFirst().questionId(),"two"))));
        assertThatThrownBy(() -> handler.execute(claim())).isInstanceOf(PermanentJobException.class);
        assertThat(jdbc.queryForObject("select count(*) from course_submission_questions where knowledge_point is not null",Integer.class)).isZero();
    }
    @Test void registrationIsActiveWithC8Apis() {
        assertThat(registry.find(JobType.COURSE_ENRICHMENT,1)).isPresent();
    }
    @Test void invalidResponsesAllRejectTheWholeBatch() {
        UUID id=fixture(2,"invalid@test.test");
        var lease=claim();
        var context=checkpoints.load(id).context(); long first=context.request().questions().getFirst().questionId();
        for(var items:List.<List<CourseKnowledgePointResponse.Item>>of(
                List.of(new CourseKnowledgePointResponse.Item(Long.MAX_VALUE,"one")),
                List.of(new CourseKnowledgePointResponse.Item(first,"one")),
                List.of(new CourseKnowledgePointResponse.Item(first,"!!!")),
                Arrays.asList((CourseKnowledgePointResponse.Item)null),
                List.of(new CourseKnowledgePointResponse.Item(null,"one")))) {
            assertThatThrownBy(() -> handler((r,c) -> new CourseKnowledgePointResponse(items)).execute(lease)).isInstanceOf(PermanentJobException.class);
            assertThat(jdbc.queryForObject("select processed_questions from course_submission_attempts",Integer.class)).isZero();
        }
    }
    @Test void transientBatchFifteenSurvivesRestartWithoutRepeatingEarlierBatches() {
        fixture(1000,"retry@test.test"); var seen=new ArrayList<Long>();
        var good=handler((request,contract) -> { seen.add(request.questions().getFirst().questionId()); return response(request); });
        for(int i=0;i<14;i++) good.execute(claim());
        var failed=claim();
        assertThatThrownBy(() -> handler((request,contract) -> { throw new RetryableJobException("HANDLER_ERROR",null); }).execute(failed)).isInstanceOf(RetryableJobException.class);
        assertThat(store.retry(failed,java.time.Instant.now(),"HANDLER_ERROR")).isTrue();
        var restarted=handler((request,contract) -> { seen.add(request.questions().getFirst().questionId()); return response(request); });
        for(int i=14;i<40;i++) restarted.execute(claim());
        assertThat(seen).hasSize(40).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("select processed_questions from course_submission_attempts",Integer.class)).isEqualTo(1000);
    }
    @Test void twoReadyJobsInterleaveAndKeepOriginalCreationTime() {
        UUID a=fixture(50,"a@test.test"),b=fixture(50,"b@test.test");
        var created=jdbc.queryForList("select id,created_at from background_jobs order by id");
        var order=new ArrayList<UUID>();
        var worker=handler((r,c) -> response(r));
        for(int i=0;i<4;i++) { var lease=claim(); order.add(UUID.fromString(mapper.readTree(lease.payloadJson()).path("submissionId").asString())); worker.execute(lease); }
        assertThat(order).containsExactly(a,b,a,b);
        assertThat(jdbc.queryForList("select id,created_at from background_jobs order by id")).isEqualTo(created);
    }
    @Test void blockedOldWorkerCannotPersistAfterExpiryAndReclaim() throws Exception {
        fixture(25,"stale@test.test"); var old=claim(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var worker=handler((r,c) -> { entered.countDown(); try { if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } return response(r); });
        try(var pool=Executors.newSingleThreadExecutor()) {
            var blocked=pool.submit(() -> worker.execute(old));
            try {
                assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
                jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second' where id=?",old.jobId());
                assertThat(store.recoverExpired(10)).isEqualTo(1); var replacement=claim();
                release.countDown(); assertThatThrownBy(() -> blocked.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
                assertThat(store.isLeaseCurrent(replacement)).isTrue();
                assertThat(jdbc.queryForObject("select count(*) from course_submission_questions where knowledge_point is not null",Integer.class)).isZero();
                assertThat(jdbc.queryForObject("select processed_questions from course_submission_attempts",Integer.class)).isZero();
            } finally { release.countDown(); }
        }
    }
    @Test void responseBeforeCheckpointRepeatsRemoteButCommittedCheckpointDoesNot() {
        UUID id=fixture(50,"crash@test.test"); var calls=new ArrayList<Long>();
        CourseKnowledgePointClient client=(r,c) -> { calls.add(r.questions().getFirst().questionId()); return response(r); };
        var lease=claim(); var turn=checkpoints.load(id);
        new CourseReviewEnrichmentService(client,difficulty,xp).enrichBatch(turn.context(),turn.parent().contract()); // process dies before checkpoint
        jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second'"); store.recoverExpired(10);
        var worker=handler(client); worker.execute(claim()); // checkpoint commits, process dies before returning to dispatcher
        worker=handler(client); worker.execute(claim());
        assertThat(calls).hasSize(3); assertThat(calls.get(0)).isEqualTo(calls.get(1)); assertThat(calls.get(2)).isNotEqualTo(calls.get(1));
    }
    @Test void unavailableVersionAndFingerprintMismatchNeverCallProviderAndFailureDelegates() {
        UUID id=fixture(1,"pin@test.test"); var calls=new AtomicInteger();
        var worker=handler((r,c) -> { calls.incrementAndGet(); return response(r); }); var lease=claim();
        jdbc.update("update course_submission_attempts set policy_version='2'");
        assertThatThrownBy(() -> worker.execute(lease)).isInstanceOf(PermanentJobException.class).hasMessage("ENRICHMENT_CONTRACT_UNAVAILABLE");
        jdbc.update("update course_submission_attempts set policy_version='1'"); jdbc.update("update question_revisions set question_text='changed'");
        assertThatThrownBy(() -> worker.execute(lease)).isInstanceOf(PermanentJobException.class); assertThat(calls).hasValue(0);
        worker.failPermanently(lease,"EXHAUSTED_ATTEMPTS");
        assertThat(jdbc.queryForObject("select state from background_jobs",String.class)).isEqualTo("DEAD");
    }
    @Test void expiryAfterStagingRollsBackWholeBatchProgressAndYield() {
        UUID id=fixture(2,"rollback@test.test"); var lease=claim(); var turn=checkpoints.load(id);
        var results=new CourseReviewEnrichmentService((r,c) -> response(r),difficulty,xp).enrichBatch(turn.context(),turn.parent().contract());
        // Expire the owned lease inside the transaction after staging, immediately before fenced yield.
        jdbc.execute("""
                create function c6_expire_lease() returns trigger language plpgsql as $$
                begin update background_jobs set lease_until=clock_timestamp()-interval '1 second'; return NEW; end $$
                """);
        jdbc.execute("create trigger c6_expire_lease after update on course_submission_attempts for each row execute function c6_expire_lease()");
        try {
            assertThatThrownBy(() -> checkpoints.checkpoint(lease,turn,results)).isInstanceOf(IllegalStateException.class).hasMessage("Stale submission lease");
            assertThat(jdbc.queryForObject("select processed_questions from course_submission_attempts",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from course_submission_questions where knowledge_point is not null",Integer.class)).isZero();
            assertThat(store.isLeaseCurrent(lease)).isTrue();
        } finally {
            jdbc.execute("drop trigger c6_expire_lease on course_submission_attempts"); jdbc.execute("drop function c6_expire_lease()");
        }
    }
    @Test void remoteContractFailureIsPermanentAndNeverFallsBack() {
        fixture(1,"remote-pin@test.test"); var calls=new AtomicInteger();
        var worker=handler((r,c) -> { calls.incrementAndGet(); throw new PermanentJobException("ENRICHMENT_CONTRACT_UNAVAILABLE"); });
        assertThatThrownBy(() -> worker.execute(claim())).isInstanceOf(PermanentJobException.class).hasMessage("ENRICHMENT_CONTRACT_UNAVAILABLE");
        assertThat(calls).hasValue(1);
        assertThat(jdbc.queryForObject("select processed_questions from course_submission_attempts",Integer.class)).isZero();
    }
    @Test void checkpointCourseLockTimeoutRetriesAfterRollbackWithoutFailingSubmission() throws Exception {
        UUID id=fixture(2,"lock-retry@test.test"); var turn=checkpoints.load(id);
        var failures=new AtomicInteger(); var providerCalls=new AtomicInteger();
        var exited=new CountDownLatch(1); var observed=new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        var worker=new CourseSubmissionHandler(checkpoints,new CourseReviewEnrichmentService((r,c) -> {
            providerCalls.incrementAndGet(); return response(r);
        },difficulty,xp),new SubmissionCompletionPort() {
            public void complete(JobLease lease,UUID submission) { throw new AssertionError("Incomplete submission"); }
            public void fail(JobLease lease,UUID submission,String code) { failures.incrementAndGet(); }
        },mapper);
        JobHandler observedHandler=new JobHandler() {
            public JobType type() { return worker.type(); }
            public int payloadVersion() { return worker.payloadVersion(); }
            public void execute(JobLease lease) {
                try { worker.execute(lease); }
                catch(RuntimeException error) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    observed.set(error); throw error;
                } finally { exited.countDown(); }
            }
            public void failPermanently(JobLease lease,String code) { worker.failPermanently(lease,code); }
        };
        var properties=new com.learne.backend.jobs.config.JobProperties(); properties.setWorkerEnabled(false);
        try(var lock=dataSource.getConnection(); var dispatcher=new JobDispatcher(store,new JobRegistry(List.of(observedHandler)),
                properties.snapshot(),java.time.Clock.systemUTC(),() -> 0)) {
            lock.setAutoCommit(false);
            try(var statement=lock.prepareStatement("select id from courses where id=? for update")) {
                statement.setLong(1,turn.parent().courseId()); statement.executeQuery().close();
            }
            assertThat(dispatcher.dispatch(QueueName.AI)).isTrue();
            assertThat(exited.await(12,TimeUnit.SECONDS)).isTrue();
            assertThat(observed.get()).isInstanceOf(RetryableJobException.class);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!"RETRY_WAIT".equals(jdbc.queryForObject("select state from background_jobs",String.class)) && System.nanoTime()<until)
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            assertThat(jdbc.queryForObject("select state from background_jobs",String.class)).isEqualTo("RETRY_WAIT");
            assertThat(jdbc.queryForObject("select attempts from background_jobs",Integer.class)).isEqualTo(1);
            assertThat(failures).hasValue(0); assertThat(providerCalls).hasValue(1);
            assertThat(jdbc.queryForObject("select processed_questions from course_submission_attempts",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select state from course_submission_attempts",String.class)).isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("select count(*) from course_submission_questions where knowledge_point is not null",Integer.class)).isZero();
            lock.rollback();
        }
    }
}
