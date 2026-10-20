package com.learne.backend.jobs.service;

import com.learne.backend.jobs.config.JobProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Dedicated bounded maintenance lane, independent of dispatcher control timers. */
@Service
public class JobMaintenanceService {
    public record Counts(int expired, int runningSecretsPurged, int terminalDeleted) {}
    private static final String MAIL_TYPES = "'REGISTRATION_OTP_MAIL','PASSWORD_RESET_MAIL','TEACHER_INVITATION_MAIL'";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final AtomicBoolean busy = new AtomicBoolean();
    private ScheduledExecutorService scheduler;

    public JobMaintenanceService(JdbcTemplate jdbc, PlatformTransactionManager manager, JobProperties.Snapshot policy) {
        this.jdbc = jdbc; this.enabled = policy.workerEnabled();
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }
    @PostConstruct
    void start() {
        if (!enabled) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "job-maintenance"); t.setDaemon(true); return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try { maintain(); }
            catch (RuntimeException ignored) { /* Retry next tick; never log payload-bearing database diagnostics. */ }
        }, 60, 60, TimeUnit.SECONDS);
    }
    public Counts maintain() {
        if (!busy.compareAndSet(false, true)) return new Counts(0, 0, 0);
        try {
            int expired = update("""
                    WITH candidates AS (
                        SELECT id FROM background_jobs WHERE type IN (%s)
                          AND expires_at<=clock_timestamp() AND state IN ('READY','RETRY_WAIT')
                        ORDER BY expires_at,id FOR UPDATE SKIP LOCKED LIMIT 500
                    )
                    UPDATE background_jobs j SET state=CASE WHEN type='TEACHER_INVITATION_MAIL' THEN 'DEAD' ELSE 'SKIPPED' END,
                        completed_at=clock_timestamp(),lease_token=NULL,lease_until=NULL,
                        secret_key_id=NULL,secret_nonce=NULL,secret_ciphertext=NULL,
                        last_error_code='JOB_EXPIRED',updated_at=clock_timestamp()
                    FROM candidates c WHERE j.id=c.id
                    """.formatted(MAIL_TYPES));
            int purged = update("""
                    WITH candidates AS (
                        SELECT id FROM background_jobs WHERE type IN (%s)
                          AND expires_at<=clock_timestamp() AND state='RUNNING' AND secret_key_id IS NOT NULL
                        ORDER BY expires_at,id FOR UPDATE SKIP LOCKED LIMIT 500
                    )
                    UPDATE background_jobs j SET secret_key_id=NULL,secret_nonce=NULL,secret_ciphertext=NULL
                    FROM candidates c WHERE j.id=c.id
                    """.formatted(MAIL_TYPES));
            int deleted = update("""
                    WITH candidates AS (
                        SELECT id FROM background_jobs WHERE state IN ('SUCCEEDED','SKIPPED','DEAD')
                          AND completed_at<clock_timestamp()-interval '30 days'
                        ORDER BY completed_at,id FOR UPDATE SKIP LOCKED LIMIT 500
                    ) DELETE FROM background_jobs j USING candidates c WHERE j.id=c.id
                    """);
            // Lock the owning submission first: active submissions never qualify, regardless of age.
            // Select only owners that still have staging, so old empty summaries cannot starve a batch.
            update("""
                    WITH candidates AS (
                        SELECT s.id FROM course_submission_attempts s
                        WHERE s.state IN ('SUCCEEDED','FAILED')
                          AND s.completed_at<clock_timestamp()-interval '30 days'
                          AND EXISTS (SELECT 1 FROM course_submission_questions q WHERE q.submission_id=s.id)
                        ORDER BY s.completed_at,s.id FOR UPDATE OF s SKIP LOCKED LIMIT 500
                    ) DELETE FROM course_submission_questions q USING candidates c WHERE q.submission_id=c.id
                    """);
            // The FK cascades any staging remaining in this separately selected summary batch.
            // Historical decisions and notifications deliberately carry scalar submission IDs.
            update("""
                    WITH candidates AS (
                        SELECT id FROM course_submission_attempts WHERE state IN ('SUCCEEDED','FAILED')
                          AND completed_at<clock_timestamp()-interval '90 days'
                        ORDER BY completed_at,id FOR UPDATE SKIP LOCKED LIMIT 500
                    ) DELETE FROM course_submission_attempts s USING candidates c WHERE s.id=c.id
                    """);
            update("""
                    WITH candidates AS (
                        SELECT id FROM user_notifications
                        WHERE created_at<clock_timestamp()-interval '90 days'
                        ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 500
                    ) DELETE FROM user_notifications n USING candidates c WHERE n.id=c.id
                    """);
            return new Counts(expired, purged, deleted);
        } finally { busy.set(false); }
    }
    private int update(String sql) { return transaction.execute(status -> jdbc.update(sql)); }
    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            try { scheduler.awaitTermination(6, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
