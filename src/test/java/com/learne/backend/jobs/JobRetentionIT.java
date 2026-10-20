package com.learne.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.jobs.service.JobMaintenanceService;
import java.util.UUID;
import java.util.concurrent.*;
import com.learne.backend.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(20)
class JobRetentionIT extends JobsPostgresTestSupport {
    @Autowired JobMaintenanceService maintenance;
    @Autowired NotificationService notifications;

    private UUID submission(String state, int age) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO course_submission_attempts(id,course_id,revision_id,teacher_id,revision_number,
                    course_name,fingerprint,original_revision_status,state,policy_version,prompt_version,
                    response_version,total_questions,created_at,completed_at)
                VALUES(?,abs(random()*1000000000)::bigint,1,1,1,'retention','fingerprint','DRAFT',?,
                    'policy','prompt','response',1,clock_timestamp()-interval '200 days',
                    CASE WHEN ? IN ('SUCCEEDED','FAILED') THEN clock_timestamp()-(? * interval '1 day') ELSE NULL END)
                """, id, state, state, age);
        jdbc.update("""
                INSERT INTO course_submission_questions(submission_id,question_revision_id,ordinal,input_fingerprint)
                VALUES(?,1,0,'fingerprint')
                """, id);
        return id;
    }

    private int count(String table, UUID id, String column) {
        // Identifiers are test constants only.
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + "=?", Integer.class, id);
    }

    @Test void stagingUsesCompletionAndThirtyDayBoundaryWhileActiveSubmissionsSurvive() {
        UUID old = submission("SUCCEEDED",31);
        UUID recent = submission("FAILED",29);
        UUID queued = submission("QUEUED",200);
        UUID processing = submission("PROCESSING",200);
        maintenance.maintain();
        assertThat(count("course_submission_questions",old,"submission_id")).isZero();
        for (UUID id : new UUID[]{recent,queued,processing})
            assertThat(count("course_submission_questions",id,"submission_id")).isOne();
        assertThat(count("course_submission_attempts",old,"id")).isOne();
    }

    @Test void summariesUseNinetyDayCompletionBoundaryAndDecisionsSurvive() {
        UUID old = submission("SUCCEEDED",91);
        UUID recent = submission("FAILED",89);
        UUID decision = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO course_review_decisions(id,revision_id,course_id,submission_id,reviewer_id,
                    teacher_id,decision,course_name,recipient_email,reviewed_at)
                VALUES(?,1,1,?,1,1,'APPROVED','retention','retention@example.test',clock_timestamp())
                """,decision,old);
        maintenance.maintain();
        assertThat(count("course_submission_attempts",old,"id")).isZero();
        assertThat(count("course_submission_questions",old,"submission_id")).isZero();
        assertThat(count("course_submission_attempts",recent,"id")).isOne();
        assertThat(count("course_review_decisions",decision,"id")).isOne();
    }

    @Test void stagingBatchSelectsAtMostFiveHundredEligibleSubmissionIds() {
        jdbc.update("""
                INSERT INTO course_submission_attempts(id,course_id,revision_id,teacher_id,revision_number,
                    course_name,fingerprint,original_revision_status,state,policy_version,prompt_version,
                    response_version,total_questions,completed_at)
                SELECT md5('retention-'||n)::uuid,n,1,1,1,'retention','fingerprint','DRAFT','FAILED',
                    'policy','prompt','response',1,clock_timestamp()-interval '31 days'
                FROM generate_series(1,501) n
                """);
        jdbc.update("""
                INSERT INTO course_submission_questions(submission_id,question_revision_id,ordinal,input_fingerprint)
                SELECT id,1,0,'fingerprint' FROM course_submission_attempts
                """);
        maintenance.maintain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM course_submission_questions",Integer.class)).isOne();
        maintenance.maintain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM course_submission_questions",Integer.class)).isZero();
    }

    private long user() {
        return jdbc.queryForObject("""
                INSERT INTO users(email,password_hash,full_name,role)
                VALUES('retention@example.test','test-only','Retention','TEACHER') RETURNING id
                """,Long.class);
    }

    private UUID notification(long user, int age) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_notifications(id,user_id,type,submission_id,course_id,revision_id,dedupe_key,message,created_at)
                VALUES(?,?,'COURSE_SUBMISSION_SUCCEEDED',?,999999,999999,?,'Historical message',
                    clock_timestamp()-(? * interval '1 day'))
                """,id,user,UUID.randomUUID(),id.toString(),age);
        return id;
    }

    @Test void notificationCreationBoundaryPreservesDeletedResourceMessageWithoutAction() {
        long user = user();
        UUID old = notification(user,91);
        UUID recent = notification(user,89);
        // Reading an old message does not extend its creation-based retention period.
        jdbc.update("UPDATE user_notifications SET read_at=clock_timestamp() WHERE id=?",old);
        maintenance.maintain();
        assertThat(count("user_notifications",old,"id")).isZero();
        var page = notifications.list(user,10,false,null);
        assertThat(page.content()).hasSize(1);
        var item = page.content().getFirst();
        assertThat(item.id()).isEqualTo(recent);
        assertThat(item.message()).isEqualTo("Historical message");
        assertThat(item.resourceAvailable()).isFalse();
        assertThat(item.actionUrl()).isNull();
    }

    @Test void summaryAndNotificationBatchesAreEachBoundedAtFiveHundred() {
        stagingBatchSelectsAtMostFiveHundredEligibleSubmissionIds();
        jdbc.update("UPDATE course_submission_attempts SET completed_at=clock_timestamp()-interval '91 days'");
        long user = user();
        jdbc.update("""
                INSERT INTO user_notifications(id,user_id,type,dedupe_key,message,created_at)
                SELECT md5('notice-'||n)::uuid,?,'COURSE_SUBMISSION_FAILED','notice-'||n,'Historical message',
                    clock_timestamp()-interval '91 days' FROM generate_series(1,501) n
                """,user);
        maintenance.maintain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM course_submission_attempts",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_notifications",Integer.class)).isOne();
        maintenance.maintain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM course_submission_attempts",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_notifications",Integer.class)).isZero();
    }

    @Test void lockedSubmissionOwnersAndNotificationsAreSkipped() throws Exception {
        UUID staged = submission("FAILED",31);
        UUID summary = submission("SUCCEEDED",91);
        UUID other = submission("FAILED",91);
        long user = user();
        UUID notice = notification(user,91);
        UUID otherNotice = notification(user,91);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var holder = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(s -> {
                jdbc.queryForList("SELECT id FROM course_submission_attempts WHERE id IN (?,?) FOR UPDATE",staged,summary);
                jdbc.queryForObject("SELECT id FROM user_notifications WHERE id=? FOR UPDATE",UUID.class,notice);
                held.countDown();
                try { if (!release.await(8,TimeUnit.SECONDS)) throw new AssertionError("lock timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            assertThat(held.await(3,TimeUnit.SECONDS)).isTrue();
            maintenance.maintain();
            assertThat(count("course_submission_questions",staged,"submission_id")).isOne();
            assertThat(count("course_submission_questions",summary,"submission_id")).isOne();
            assertThat(count("course_submission_attempts",summary,"id")).isOne();
            assertThat(count("course_submission_attempts",other,"id")).isZero();
            assertThat(count("user_notifications",notice,"id")).isOne();
            assertThat(count("user_notifications",otherNotice,"id")).isZero();
            release.countDown();
            holder.get(3,TimeUnit.SECONDS);
            maintenance.maintain();
            assertThat(count("course_submission_questions",staged,"submission_id")).isZero();
            assertThat(count("course_submission_attempts",summary,"id")).isZero();
            assertThat(count("user_notifications",notice,"id")).isZero();
        } finally {
            release.countDown(); pool.shutdownNow();
            assertThat(pool.awaitTermination(3,TimeUnit.SECONDS)).isTrue();
        }
    }
}
