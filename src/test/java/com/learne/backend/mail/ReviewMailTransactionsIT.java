package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.learne.backend.admin.content.service.AdminContentReviewService;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.mail.service.MailPayloadCodec;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Import(MailDeliveryIT.TransportFixture.class)
@TestPropertySource(properties = {
        "app.job-crypto.active-key-id=mail-test",
        "app.job-crypto.keys-json={\"mail-test\":\"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=\"}",
        "app.mail.frontend-base-url=https://learne.test"})
class ReviewMailTransactionsIT extends MailPostgresTestSupport {
    @Autowired AdminContentReviewService reviews;
    @Autowired com.learne.backend.admin.content.service.ReviewDecisionService decisions;
    @Autowired UserRepository users;
    @Autowired MailPayloadCodec codec;
    @Autowired JobRegistry registry;
    @Autowired MailDeliveryIT.FakeTransport transport;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @MockitoSpyBean JobStore jobStore;

    @BeforeEach void seed() {
        transport.delivered.clear(); transport.nextFailure = null; transport.onSend = null;
        jdbc.update("insert into users(id,email,password_hash,full_name,role) values(7,'teacher@example.test','hash','Snapshot Teacher','TEACHER'),(8,'admin@example.test','hash','Admin','ADMIN'),(9,'owner@example.test','hash','Other Teacher','TEACHER')");
        jdbc.update("insert into courses(id,teacher_id,name,type,level,status) values(11,9,'Live name','COMPREHENSIVE','B1','DRAFT')");
        jdbc.update("insert into course_revisions(id,course_id,teacher_id,revision_number,status,name,type,level,pricing_type,proposed_price_amount_vnd) values(12,11,7,1,'PENDING_REVIEW','Snapshot course','COMPREHENSIVE','B1','PAID',100)");
        jdbc.update("insert into topic_revisions(id,revision_id,name) values(21,12,'Topic')");
        jdbc.update("insert into lesson_revisions(id,topic_revision_id,teacher_id,title,skill_type) values(22,21,7,'Lesson','READING')");
        jdbc.update("insert into question_revisions(id,lesson_revision_id,teacher_id,type,question_text) values(23,22,7,'MULTIPLE_CHOICE','Question')");
    }
    void review(String path) {
        var admin = users.findById(8L).orElseThrow();
        switch (path) {
            case "approve-course" -> reviews.approveCourse(admin, 11L);
            case "approve-revision" -> reviews.approveRevision(admin, 12L);
            case "reject-course" -> reviews.rejectCourse(admin, 11L, "Declared reason");
            case "reject-revision" -> reviews.rejectRevision(admin, 12L, "Declared reason");
            default -> throw new IllegalArgumentException(path);
        }
    }
    JobLease claim() {
        return store.claim(QueueName.MAIL_NOTICE, Map.of(JobType.COURSE_REVIEW_RESULT_MAIL, Set.of(1)), Duration.ofSeconds(30)).orElseThrow();
    }
    void execute(JobLease lease) { registry.find(lease.type(), 1).orElseThrow().execute(lease); }
    void assertNoIntent() {
        for (String table : List.of("background_jobs", "course_review_decisions"))
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).isZero();
    }
    @Test void mandatoryRejectsOutsideTransaction() {
        assertThatThrownBy(() -> decisions.recordAndEnqueue(null, null))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertNoIntent();
    }
    @Test void reviewLinksOnlySucceededSubmissionForExactRevision() {
        UUID matching=UUID.randomUUID();
        jdbc.update("""
                insert into course_submission_attempts(id,course_id,revision_id,teacher_id,revision_number,course_name,fingerprint,
                original_revision_status,state,policy_version,prompt_version,response_version,total_questions,completed_at)
                values(?,11,12,7,1,'Snapshot course',repeat('a',64),'DRAFT','SUCCEEDED','1','1','1',1,clock_timestamp())
                """,matching);
        jdbc.update("""
                insert into course_submission_attempts(id,course_id,revision_id,teacher_id,revision_number,course_name,fingerprint,
                original_revision_status,state,policy_version,prompt_version,response_version,total_questions,completed_at)
                values(?,11,999,7,2,'Other',repeat('a',64),'DRAFT','SUCCEEDED','1','1','1',1,clock_timestamp())
                """,UUID.randomUUID());
        review("reject-revision");
        assertThat(jdbc.queryForObject("select submission_id from course_review_decisions",UUID.class)).isEqualTo(matching);
        assertThat(codec.decode(claim().type(),jdbc.queryForObject("select payload::text from background_jobs",String.class)).submissionId()).isEqualTo(matching);
    }
    @Test void nanosecondReviewTimeIsCanonicalizedAndDelivered() {
        var nanos = Instant.parse("2026-10-19T00:00:00.123456789Z");
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(nanos);
            review("approve-revision");
        }
        var lease = claim(); var p = codec.decode(lease.type(), lease.payloadJson());
        assertThat(p.values().get("reviewedAt")).isEqualTo("2026-10-19T00:00:00.123456Z");
        execute(lease); assertThat(transport.delivered).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings = {"approve-course", "approve-revision", "reject-course", "reject-revision"})
    void allPathsPersistExactSnapshotAndDeliverLegacyNullSubmission(String path) {
        review(path);
        var decision = jdbc.queryForMap("select * from course_review_decisions");
        assertThat(decision).containsEntry("teacher_id", 7L).containsEntry("reviewer_id", 8L)
                .containsEntry("course_id", 11L).containsEntry("revision_id", 12L).containsEntry("submission_id", null);
        var lease = claim(); var p = codec.decode(lease.type(), lease.payloadJson());
        assertThat(p.decisionId()).isEqualTo(decision.get("id")); assertThat(p.submissionId()).isNull();
        assertThat(p.recipientUserId()).isEqualTo(7); assertThat(p.recipientName()).isEqualTo("Snapshot Teacher");
        assertThat(p.recipientEmail()).isEqualTo("teacher@example.test");
        assertThat(Instant.parse(p.values().get("reviewedAt"))).isEqualTo(((java.sql.Timestamp) decision.get("reviewed_at")).toInstant());
        assertThat(p.values()).containsEntry("courseName", "Snapshot course")
                .containsEntry("decision", path.startsWith("approve") ? "APPROVED" : "REJECTED")
                .containsEntry("rejectionReason", path.startsWith("approve") ? "" : "Declared reason");
        var row = jdbc.queryForMap("select * from background_jobs");
        assertThat(row).containsEntry("queue", "MAIL_NOTICE").containsEntry("priority", 0)
                .containsEntry("dedupe_key", "course-review:" + p.decisionId() + ":7");
        for (String key : List.of("expires_at", "secret_nonce", "secret_ciphertext", "secret_key_id")) assertThat(row.get(key)).isNull();
        execute(lease); assertThat(transport.delivered).hasSize(1);
        assertThatThrownBy(() -> review(path)).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        assertThat(jdbc.queryForObject("select count(*) from course_review_decisions", Long.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings = {"DRAFT", "SUPERSEDED", "PRICING", "STALE"})
    void invalidReviewsProduceNothing(String state) {
        if (state.equals("PRICING")) jdbc.update("update course_revisions set proposed_price_amount_vnd=0");
        else if (state.equals("STALE")) jdbc.update("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values(11,7,2,'DRAFT','New','COMPREHENSIVE','B1')");
        else jdbc.update("update course_revisions set status=?", state);
        assertThatThrownBy(() -> review("approve-revision")).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        assertNoIntent();
    }
    @Test void enqueueFailureAfterPublicationFlushRollsBackEntireGraphAndDecision() {
        JobStore target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobStore);
        doAnswer(call -> {
            entityManager.flush();
            assertThat(jdbc.queryForObject("select status from courses where id=11", String.class)).isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject("select count(*) from questions", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from course_review_decisions", Long.class)).isEqualTo(1);
            call.callRealMethod(); throw new JobEnqueueException(null);
        }).when(target).enqueue(any());
        assertThatThrownBy(() -> review("approve-course")).isInstanceOf(JobEnqueueException.class);
        assertNoIntent();
        assertThat(jdbc.queryForObject("select status from course_revisions where id=12", String.class)).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForMap("select status,price_amount_vnd,published_at,name from courses where id=11"))
                .containsEntry("status", "DRAFT").containsEntry("price_amount_vnd", null).containsEntry("published_at", null).containsEntry("name", "Live name");
        for (String table : List.of("topics", "lessons", "questions")) assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).isZero();
    }
    @Test void sameRevisionTwoCyclesHaveDistinctHistoryAndOldMailSurvivesDeletion() {
        review("reject-revision");
        var old = claim();
        jdbc.update("update course_revisions set status='PENDING_REVIEW',name='Edited course',rejection_reason=null where id=12");
        review("approve-revision");
        assertThat(jdbc.queryForObject("select count(distinct id) from course_review_decisions", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(distinct dedupe_key) from background_jobs", Long.class)).isEqualTo(2);
        jdbc.update("delete from courses where id=11");
        execute(old);
        assertThat(transport.delivered).hasSize(1);
        assertThat(transport.delivered.getFirst().getText()).contains("Snapshot course", "Declared reason").doesNotContain("Edited course");
    }
    @Test void smtpFailureAfterCommitRetriesWithoutReviewingAgain() {
        review("approve-revision");
        var before = jdbc.queryForMap("select * from course_review_decisions");
        transport.nextFailure = new RetryableJobException("THROTTLED", null);
        transport.onSend = () -> assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var lease = claim();
        assertThatThrownBy(() -> execute(lease)).isInstanceOf(RetryableJobException.class);
        assertThat(jdbc.queryForObject("select status from course_revisions where id=12", String.class)).isEqualTo("APPROVED");
        assertThat(store.retry(lease, Instant.now().minusSeconds(1), "THROTTLED")).isTrue();
        execute(claim()); assertThat(transport.delivered).hasSize(1);
        assertThat(jdbc.queryForMap("select * from course_review_decisions")).isEqualTo(before);
    }
    @Test void changedTeacherEmailSkipsWithoutRetargeting() {
        review("reject-course"); jdbc.update("update users set email='changed@example.test' where id=7");
        execute(claim()); assertThat(transport.delivered).isEmpty();
        assertThat(jdbc.queryForObject("select state from background_jobs", String.class)).isEqualTo("SKIPPED");
    }
    @Test void invalidMetadataRollsBackInsteadOfCommittingWithoutIntent() {
        jdbc.update("update users set email='invalid-address' where id=7");
        assertThatThrownBy(() -> review("approve-course")).isInstanceOf(IllegalArgumentException.class);
        assertNoIntent();
        assertThat(jdbc.queryForObject("select status from course_revisions where id=12", String.class)).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from questions", Long.class)).isZero();
    }
    @Test void blockedSmtpCannotHoldReviewTransactionOrUndoRejection() throws Exception {
        review("reject-revision");
        var before = jdbc.queryForMap("select * from course_review_decisions");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        transport.onSend = () -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            try { assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        };
        var lease = claim();
        try {
            var sending = executor.submit(() -> execute(lease));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(sending.isDone()).isFalse();
            assertThat(jdbc.queryForObject("select status from course_revisions where id=12", String.class)).isEqualTo("REJECTED");
            assertThat(jdbc.queryForMap("select * from course_review_decisions")).isEqualTo(before);
            release.countDown(); sending.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            transport.onSend = null;
        }
    }
    @Test void concurrentApproveRejectAcceptsExactlyOneDecision() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (String path : List.of("approve-course", "reject-revision")) tasks.add(executor.submit(() -> {
                assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                try { review(path); return true; }
                catch (com.learne.backend.shared.api.ApiException expected) { return false; }
            }));
            start.countDown();
            int accepted = 0;
            for (var task : tasks) if (task.get(5, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from course_review_decisions", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isEqualTo(1);
        } finally {
            start.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }
}
