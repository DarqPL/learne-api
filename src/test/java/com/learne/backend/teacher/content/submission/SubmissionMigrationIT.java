package com.learne.backend.teacher.content.submission;

import com.learne.backend.jobs.JobsPostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import com.learne.backend.notification.NotificationRepository;
import com.learne.backend.teacher.content.enrichment.*;
import com.learne.backend.jobs.model.QueueName;
import com.learne.backend.student.learning.ai.AiServiceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.Map;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class SubmissionMigrationIT extends JobsPostgresTestSupport {
    @Autowired SubmissionRepository submissions;
    @Autowired NotificationRepository notifications;
    private UUID attempt(long course, String state, int total, int processed, boolean completed) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into course_submission_attempts
                (id,course_id,revision_id,teacher_id,revision_number,course_name,fingerprint,
                 original_revision_status,state,policy_version,prompt_version,response_version,
                 total_questions,processed_questions,completed_at)
                values (?,?,11,21,1,'Historical course','hash','DRAFT',?,'1','1','1',?,?,
                        case when ? then clock_timestamp() else null end)
                """, id, course, state, total, processed, completed);
        return id;
    }
    private void question(UUID id, long question, int ordinal, String kp, String difficulty, Integer xp) {
        jdbc.update("insert into course_submission_questions values (?,?,?,'input',?,?,?)",
                id, question, ordinal, kp, difficulty, xp);
    }
    @Test void createsSubmissionAndNotificationTables() {
        assertThat(jdbc.queryForList("select tablename from pg_tables where schemaname='public'", String.class))
                .contains("course_submission_attempts", "course_submission_questions", "user_notifications");
    }

    @Test void validatesProgressAndCompletionAndAllowsTerminalHistory() {
        for (int total : new int[]{0, 1001})
            assertThatThrownBy(() -> attempt(1, "QUEUED", total, 0, false)).isInstanceOf(DataIntegrityViolationException.class);
        for (int processed : new int[]{-1, 2})
            assertThatThrownBy(() -> attempt(1, "QUEUED", 1, processed, false)).isInstanceOf(DataIntegrityViolationException.class);
        for (String state : new String[]{"SUCCEEDED", "FAILED"})
            assertThatThrownBy(() -> attempt(1, state, 1, 0, false)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> attempt(1, "PROCESSING", 1, 0, true)).isInstanceOf(DataIntegrityViolationException.class);
        attempt(1, "SUCCEEDED", 1000, 1000, true);
        attempt(1, "FAILED", 1, 0, true);
        attempt(1, "QUEUED", 1, 0, false);
        assertThatThrownBy(() -> attempt(1, "PROCESSING", 1, 0, false)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @Timeout(15) void concurrentTransactionsAllowExactlyOneActiveAttempt() throws Exception {
        var inserted = new CountDownLatch(1);
        var competing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var competingPid = new java.util.concurrent.atomic.AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                attempt(99, "QUEUED", 1, 0, false); inserted.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return true;
            }));
            assertThat(inserted.await(3, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> {
                assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
                    competingPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                    competing.countDown();
                    return attempt(99, "PROCESSING", 1, 0, false);
                })).isInstanceOf(DataIntegrityViolationException.class);
            });
            assertThat(competing.await(3, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean waiting;
            do {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",
                        Boolean.class,competingPid.get()));
                if (!waiting) Thread.sleep(10);
            } while (!waiting && System.nanoTime() < deadline);
            assertThat(waiting).as("competing insert waits on the uncommitted partial unique index entry").isTrue();
            release.countDown(); first.get(6, TimeUnit.SECONDS); second.get(6, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select count(*) from course_submission_attempts where course_id=99", Integer.class)).isOne();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void manifestRequiresUniqueIdentityOrderAndCompleteV1Metadata() {
        UUID id = attempt(1, "QUEUED", 1000, 0, false);
        question(id, 1, 0, null, null, null);
        question(id, 2, 1, "grammar", "easy", 10);
        question(id, 3, 2, "grammar", "medium", 20);
        question(id, 4, 3, "grammar", "hard", 30);
        assertThatThrownBy(() -> question(id, 1, 4, null, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> question(id, 5, 0, null, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> question(id, 5, -1, null, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        for (Object[] metadata : new Object[][]{{null,"easy",10},{"kp",null,10},{"kp","easy",null},
                {"", "easy",10},{"kp","easy",20},{"kp","medium",30},{"kp","hard",10},{"kp","other",10},
                {"kp",null,null},{null,"easy",null},{null,null,10}}) {
            assertThatThrownBy(() -> question(id, 5, 4, (String)metadata[0], (String)metadata[1], (Integer)metadata[2]))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update("delete from course_submission_attempts where id=?", id);
        assertThat(jdbc.queryForObject("select count(*) from course_submission_questions", Integer.class)).isZero();
    }

    @Test void sourceReadsDistinctActiveTuplesAndRealGateFailsClosedOnDatabaseFailure() {
        UUID queued = attempt(1, "QUEUED", 1, 0, false);
        attempt(2, "PROCESSING", 1, 0, false);
        attempt(3, "SUCCEEDED", 1, 1, true);
        attempt(4, "FAILED", 1, 0, true);
        jdbc.update("update course_submission_attempts set prompt_version='2',response_version='1' where id=?", queued);
        jdbc.update("update course_submission_attempts set policy_version='old-policy' where state in ('SUCCEEDED','FAILED')");
        assertThat(submissions.activeContracts()).containsExactlyInAnyOrder(EnrichmentContract.V1, new EnrichmentContract("1","2","1"));
        UUID duplicate = attempt(5, "PROCESSING", 1, 0, false);
        assertThat(submissions.activeContracts()).hasSize(2);
        var transport = mock(EnrichmentHttpTransport.class);
        when(transport.capabilities(any(), any())).thenReturn(new EnrichmentHttpTransport.Response(200,
                HttpHeaders.of(Map.of(), (a,b) -> true),
                "{\"supportedContracts\":[{\"promptVersion\":\"1\",\"responseVersion\":\"1\"}]}".getBytes(StandardCharsets.UTF_8)));
        try (var gate = new EnrichmentCapabilityGate(new EnrichmentCapabilities(
                new AiServiceProperties("http://127.0.0.1:1/",8000,true,"test"), transport),true,false, () -> List.of(submissions))) {
            gate.start(); assertThat(gate.allows(QueueName.AI)).isFalse(); verifyNoInteractions(transport);
            gate.refresh(); assertThat(gate.state()).isEqualTo(EnrichmentCapabilityGate.SafeState.UNSUPPORTED_TUPLE);
            jdbc.update("update course_submission_attempts set prompt_version='1',response_version='1' where id=?", queued);
            gate.refresh(); assertThat(gate.allows(QueueName.AI)).isTrue();
            jdbc.update("update course_submission_attempts set policy_version='legacy' where id=?", duplicate);
            assertThat(submissions.activeContracts()).contains(new EnrichmentContract("legacy","1","1"));
            gate.refresh(); assertThat(gate.state()).isEqualTo(EnrichmentCapabilityGate.SafeState.UNSUPPORTED_POLICY);
            jdbc.execute("alter table course_submission_attempts rename to unavailable_attempts");
            try {
                gate.refresh(); assertThat(gate.state()).isEqualTo(EnrichmentCapabilityGate.SafeState.SOURCE_FAILURE);
                assertThat(gate.allows(QueueName.AI)).isFalse();
            } finally { jdbc.execute("alter table unavailable_attempts rename to course_submission_attempts"); }
        }
    }

    private long user() {
        return jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values (?, 'test','Teacher','TEACHER') returning id",
                Long.class, UUID.randomUUID()+"@example.test");
    }

    @Test void terminalHistorySurvivesNormalContentRebuildDeletionAndNotificationRetainsMessage() {
        long teacher = user();
        long course = jdbc.queryForObject("insert into courses(teacher_id,name,type,level,status) values (?,'Course','GENERAL','A1','DRAFT') returning id", Long.class, teacher);
        long revision = jdbc.queryForObject("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,1,'DRAFT','Course','GENERAL','A1') returning id", Long.class,course,teacher);
        long topic = jdbc.queryForObject("insert into topic_revisions(revision_id,name) values (?,'Topic') returning id",Long.class,revision);
        long lesson = jdbc.queryForObject("insert into lesson_revisions(topic_revision_id,teacher_id,title,skill_type) values (?,?,'Lesson','READING') returning id",Long.class,topic,teacher);
        long question = jdbc.queryForObject("insert into question_revisions(lesson_revision_id,teacher_id,type,question_text) values (?,?,'MCQ','Question') returning id",Long.class,lesson,teacher);
        UUID id = attempt(course,"SUCCEEDED",1,1,true);
        jdbc.update("update course_submission_attempts set revision_id=?,teacher_id=? where id=?",revision,teacher,id);
        question(id,question,0,"grammar","easy",10);
        assertThat(submissions.findSummary(id)).get().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo(id); assertThat(summary.status()).isEqualTo("SUCCEEDED");
            assertThat(summary.completedAt()).isNotNull(); assertThat(summary.message()).isNull();
        });
        assertThat(submissions.findSummary(UUID.randomUUID())).isEmpty();
        assertThat(submissions.manifest(id)).singleElement().satisfies(row -> assertThat(row.questionRevisionId()).isEqualTo(question));
        UUID notification = UUID.randomUUID();
        var intent = new NotificationRepository.Intent(notification, teacher,"COURSE_SUBMISSION_SUCCEEDED",id,course,revision,"done:"+id,"Historical message");
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThat(notifications.insertIfAbsent(intent)).isTrue();
            assertThat(notifications.insertIfAbsent(intent)).isFalse();
        });
        jdbc.update("delete from question_revisions where id=?",question);
        jdbc.update("insert into question_revisions(lesson_revision_id,teacher_id,type,question_text) values (?,?,'MCQ','Rebuilt')",lesson,teacher);
        jdbc.update("delete from course_revisions where id=?",revision);
        jdbc.update("delete from courses where id=?",course);
        assertThat(submissions.manifest(id)).hasSize(1);
        assertThat(submissions.findSummary(id)).isPresent();
        jdbc.update("delete from course_submission_attempts where id=?",id);
        assertThat(jdbc.queryForObject("select message from user_notifications where id=?",String.class,notification)).isEqualTo("Historical message");
        assertThat(notifications.countUnread(teacher)).isOne();
        assertThat(notifications.markRead(notification,teacher+1)).isFalse();
        assertThat(notifications.markRead(notification,teacher)).isTrue();
        assertThat(notifications.markRead(notification,teacher)).isFalse();
        assertThat(notifications.countUnread(teacher)).isZero();
        jdbc.update("delete from users where id=?",teacher);
        assertThat(jdbc.queryForObject("select count(*) from user_notifications",Integer.class)).isZero();
    }

    @Test void notificationIntentRollsBackWithCallerTransactionAndTypeIsConstrained() {
        long teacher = user();
        var intent = new NotificationRepository.Intent(UUID.randomUUID(),teacher,"COURSE_SUBMISSION_FAILED",null,null,null,"failure","Failed");
        assertThatThrownBy(() -> notifications.insertIfAbsent(intent))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        new TransactionTemplate(manager).executeWithoutResult(status -> { notifications.insertIfAbsent(intent); status.setRollbackOnly(); });
        assertThat(notifications.countUnread(teacher)).isZero();
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> notifications.insertIfAbsent(
                new NotificationRepository.Intent(UUID.randomUUID(),teacher,"OTHER",null,null,null,"bad","Bad"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void constructionHasNoIoAndPublicSummaryHasExactlyTheApprovedFields() {
        var database = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        new SubmissionRepository(database);
        new NotificationRepository(database);
        verifyNoInteractions(database);
        assertThat(java.util.Arrays.stream(SubmissionResponse.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("id","courseId","revisionId","status","processedQuestions","totalQuestions",
                        "createdAt","completedAt","failureCode","message");
    }
}
