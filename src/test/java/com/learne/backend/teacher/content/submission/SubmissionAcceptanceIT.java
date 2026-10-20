package com.learne.backend.teacher.content.submission;

import com.learne.backend.jobs.JobsPostgresTestSupport;
import com.learne.backend.jobs.service.JobEnqueuer;
import com.learne.backend.teacher.content.enrichment.CourseKnowledgePointClient;
import com.learne.backend.teacher.content.enrichment.EnrichmentContract;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubmissionAcceptanceIT extends JobsPostgresTestSupport {
    @Autowired SubmissionAcceptanceService acceptance;
    @Autowired SubmissionProjectionReader projections;
    @Autowired SubmissionRepository submissions;
    @Autowired jakarta.persistence.EntityManagerFactory emf;
    @MockitoSpyBean JobEnqueuer jobs;
    @MockitoSpyBean CourseKnowledgePointClient ai;
    private long teacher, course, revision;

    private void fixture(int count) {
        teacher=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('accept@test.test','test','Teacher','TEACHER') returning id",Long.class);
        course=jdbc.queryForObject("insert into courses(teacher_id,name,type,level,status) values (?,'Course','COMPREHENSIVE','A1','DRAFT') returning id",Long.class,teacher);
        revision=jdbc.queryForObject("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,1,'DRAFT','Course','COMPREHENSIVE','A1') returning id",Long.class,course,teacher);
        long topic=jdbc.queryForObject("insert into topic_revisions(revision_id,name) values (?,'Topic') returning id",Long.class,revision);
        long lesson=jdbc.queryForObject("insert into lesson_revisions(topic_revision_id,teacher_id,title,skill_type,content) values (?,?,'Lesson','READING','Content') returning id",Long.class,topic,teacher);
        jdbc.update("insert into question_revisions(lesson_revision_id,teacher_id,type,question_text,order_index) select ?,?,'MULTIPLE_CHOICE','Question '||n,n from generate_series(1,?) n",lesson,teacher,count);
    }
    private SubmissionResponse accept() { return acceptance.accept(teacher,course); }
    private void empty() {
        for(String table:java.util.List.of("course_submission_attempts","course_submission_questions","background_jobs"))
            assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isZero();
    }
    @Test void ownerAndCurrentActorAreRequired() {
        fixture(1);
        assertThatThrownBy(() -> acceptance.accept(teacher+100,course)).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        for(String update:java.util.List.of("role='STUDENT'","is_active=false","is_locked=true")) {
            jdbc.update("update users set role='TEACHER',is_active=true,is_locked=false where id=?",teacher);
            jdbc.update("update users set "+update+" where id=?",teacher);
            assertThatThrownBy(this::accept).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        }
        empty();
    }
    @Test void revisionOwnershipIsReloaded() {
        fixture(1);
        long other=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('other@test.test','test','Other','TEACHER') returning id",Long.class);
        assertThatThrownBy(() -> acceptance.accept(other,course)).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        jdbc.update("update course_revisions set teacher_id=?",other);
        assertThatThrownBy(this::accept).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        empty();
    }
    @Test void latestRevisionMustBeEditableAndHaveContent() {
        fixture(1);
        jdbc.update("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,2,'PENDING_REVIEW','Latest','COMPREHENSIVE','A1')",course,teacher);
        assertThatThrownBy(this::accept).hasMessageContaining("draft or rejected");
        jdbc.update("update course_revisions set status='DRAFT' where revision_number=2");
        assertThatThrownBy(this::accept).hasMessageContaining("at least one topic");
        empty();
    }
    @Test void rejectedRevisionCanBeAccepted() {
        fixture(1); jdbc.update("update course_revisions set status='REJECTED'");
        assertThat(accept().status()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select original_revision_status from course_submission_attempts",String.class)).isEqualTo("REJECTED");
    }
    @Test void thousandQuestionsPersistExactManifestAndPinnedJobWithoutGraphLoads() {
        fixture(1000);
        var expected=projections.capture(course,EnrichmentContract.V1);
        var stats=emf.unwrap(org.hibernate.SessionFactory.class).getStatistics(); stats.setStatisticsEnabled(true); stats.clear();
        long heapBefore=usedHeap(),start=System.nanoTime();
        var response=accept();
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),heapDelta=usedHeap()-heapBefore;
        assertThat(response.totalQuestions()).isEqualTo(1000); assertThat(response.processedQuestions()).isZero();
        assertThat(response.status()).isEqualTo("QUEUED"); assertThat(response.editingLocked()).isTrue();
        assertThat(submissions.findSummary(response.id())).contains(response);
        var manifest=submissions.manifest(response.id()); assertThat(manifest).hasSize(1000);
        for(int i=0;i<1000;i++) {
            assertThat(manifest.get(i).ordinal()).isEqualTo(i);
            assertThat(manifest.get(i).questionRevisionId()).isEqualTo(expected.manifestEntries().get(i).questionRevisionId());
            assertThat(manifest.get(i).inputFingerprint()).isEqualTo(expected.manifestEntries().get(i).perQuestionInputFingerprint());
        }
        var attempt=jdbc.queryForMap("select * from course_submission_attempts");
        assertThat(attempt).containsEntry("fingerprint",expected.globalFingerprint()).containsEntry("policy_version","1").containsEntry("prompt_version","1").containsEntry("response_version","1");
        var job=jdbc.queryForMap("select queue,type,payload_version,dedupe_key,payload::text from background_jobs");
        assertThat(job).containsEntry("queue","AI").containsEntry("type","COURSE_ENRICHMENT").containsEntry("payload_version",1).containsEntry("dedupe_key","course-submit:"+response.id());
        assertThat(jdbc.queryForObject("select payload = jsonb_build_object('submissionId',?::text) from background_jobs",Boolean.class,response.id().toString())).isTrue();
        assertThat(stats.getCollectionLoadCount()).isZero();
        System.out.printf("C4b acceptance 1000: elapsedMs=%d heapDeltaBytes=%d jpaStatements=%d entityLoads=%d collectionLoads=%d%n",elapsed,heapDelta,stats.getPrepareStatementCount(),stats.getEntityLoadCount(),stats.getCollectionLoadCount());
        stats.setStatisticsEnabled(false);
        assertThat(jdbc.queryForObject("select status from course_revisions where id=?",String.class,revision)).isEqualTo("DRAFT");
        verifyNoInteractions(ai);
    }
    private static long usedHeap() { return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(); }
    @Test void overLimitAndNoContentLeaveNoLogicalLock() {
        fixture(1001); assertThatThrownBy(this::accept).hasMessageContaining("1000"); empty();
        jdbc.update("delete from question_revisions");
        assertThatThrownBy(this::accept).hasMessageContaining("at least one topic"); empty();
    }
    @Test void duplicateActiveReturnsPersistedProgress() {
        fixture(1); var first=accept();
        jdbc.update("update course_submission_attempts set state='PROCESSING',processed_questions=1");
        var persisted=submissions.findSummary(first.id()).orElseThrow();
        assertThat(accept()).isEqualTo(persisted);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isEqualTo(1);
    }
    @Test void activeDifferentRevisionIsConflict() {
        fixture(1); accept();
        jdbc.update("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,2,'DRAFT','Latest','COMPREHENSIVE','A1')",course,teacher);
        assertThatThrownBy(this::accept).hasMessageContaining("active submission");
    }
    @Test void simultaneousSubmitsHaveOneAttemptAndOneJob() throws Exception {
        fixture(1); var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<SubmissionResponse> action=() -> { gate.await(); return accept(); };
            var a=pool.submit(action); var b=pool.submit(action); gate.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from course_submission_attempts",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isEqualTo(1);
    }
    @Test void enqueueFailureRollsBackAttemptAndManifestThenAllowsRetry() {
        fixture(1);
        JobEnqueuer target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobs);
        doThrow(new IllegalStateException("enqueue unavailable")).when(target).enqueue(any());
        assertThatThrownBy(this::accept).hasMessageContaining("enqueue unavailable"); empty();
        doCallRealMethod().when(target).enqueue(any()); assertThat(accept().status()).isEqualTo("QUEUED");
    }
    @Test void blockedFakeAiDoesNotDelayAcceptance() throws Exception {
        fixture(1); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); release.await(5,TimeUnit.SECONDS); return null; })
                .when(ai).enrichKnowledgePoints(any(),any());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var blocked=pool.submit(() -> ai.enrichKnowledgePoints(null,EnrichmentContract.V1));
            try { assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue(); assertThat(accept().status()).isEqualTo("QUEUED"); assertThat(blocked.isDone()).isFalse(); verify(ai,times(1)).enrichKnowledgePoints(any(),any()); }
            finally { release.countDown(); }
        }
    }
}
