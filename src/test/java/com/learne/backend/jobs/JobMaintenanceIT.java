package com.learne.backend.jobs;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.JobMaintenanceService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(15)
class JobMaintenanceIT extends JobsPostgresTestSupport {
    @Autowired JobMaintenanceService maintenance;
    UUID insert(JobType type, Instant expiry) {
        UUID id = UUID.randomUUID();
        QueueName queue = type == JobType.MEDIA_CLEANUP ? QueueName.MEDIA : QueueName.MAIL_AUTH;
        new TransactionTemplate(manager).executeWithoutResult(s -> enqueuer.enqueue(new JobSpec(id, queue,
                type, 1, type == JobType.TEACHER_INVITATION_MAIL ? 1 : 0, id.toString(), "{}", expiry,
                new Ciphertext("test-v1", new byte[12], new byte[16]))));
        return id;
    }
    Map<String,Object> row(UUID id) { return jdbc.queryForMap("select * from background_jobs where id=?", id); }
    void purged(UUID id, String state) {
        assertThat(row(id)).containsEntry("state", state).containsEntry("secret_key_id", null)
                .containsEntry("secret_nonce", null).containsEntry("secret_ciphertext", null);
    }
    @Test void expiresOnlyKnownAuthMailAndNeverFutureOrNonmailOrUnknown() {
        for (var type : List.of(JobType.REGISTRATION_OTP_MAIL, JobType.PASSWORD_RESET_MAIL, JobType.TEACHER_INVITATION_MAIL)) {
            UUID id = insert(type, Instant.now().minusSeconds(60));
            if (type == JobType.PASSWORD_RESET_MAIL) jdbc.update("update background_jobs set state='RETRY_WAIT' where id=?", id);
            assertThat(maintenance.maintain().expired()).isOne();
            purged(id, type == JobType.TEACHER_INVITATION_MAIL ? "DEAD" : "SKIPPED");
            assertThat(row(id)).containsEntry("lease_token", null).containsEntry("lease_until", null);
            assertThat(row(id).get("completed_at")).isNotNull();
        }
        UUID future = insert(JobType.REGISTRATION_OTP_MAIL, Instant.now().plusSeconds(60));
        UUID media = insert(JobType.MEDIA_CLEANUP, Instant.now().minusSeconds(60));
        UUID unknown = insert(JobType.REGISTRATION_OTP_MAIL, Instant.now().minusSeconds(60));
        jdbc.update("update background_jobs set type='FUTURE_MAIL' where id=?", unknown);
        UUID ai = insert(JobType.MEDIA_CLEANUP, Instant.now().minusSeconds(60));
        jdbc.update("update background_jobs set queue='AI',type='COURSE_ENRICHMENT' where id=?", ai);
        assertThat(maintenance.maintain().expired()).isZero();
        for (UUID id : List.of(future, media, unknown, ai)) assertThat(row(id)).containsEntry("state", "READY").containsEntry("secret_key_id", "test-v1");
    }
    @Test void runningExpiryOnlyPurgesSecretsThenRecoveryAllowsTerminalizationWithoutSecret() {
        UUID id = insert(JobType.REGISTRATION_OTP_MAIL, Instant.now().minusSeconds(60));
        var lease = store.claim(QueueName.MAIL_AUTH, Map.of(JobType.REGISTRATION_OTP_MAIL, Set.of(1)), Duration.ofMinutes(1)).orElseThrow();
        var before = row(id);
        assertThat(maintenance.maintain().runningSecretsPurged()).isOne();
        purged(id, "RUNNING");
        for (String column : List.of("lease_token", "lease_until", "attempts", "updated_at")) assertThat(row(id).get(column)).isEqualTo(before.get(column));
        assertThat(store.isLeaseCurrent(lease)).isTrue();
        jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second' where id=?", id);
        assertThat(store.recoverExpired(500)).isOne();
        assertThat(maintenance.maintain().expired()).isOne(); purged(id, "SKIPPED");
    }
    @Test void boundedExpiryAndTerminalRetentionNeverDeleteOldActiveJobs() {
        jdbc.update("""
                insert into background_jobs(id,queue,type,payload_version,dedupe_key,payload,max_attempts,expires_at)
                select md5('maintenance-'||n)::uuid,'MAIL_AUTH','REGISTRATION_OTP_MAIL',1,'maintenance-'||n,'{}'::jsonb,3,
                    clock_timestamp()-interval '1 second' from generate_series(1,501) n
                """);
        assertThat(maintenance.maintain().expired()).isEqualTo(500);
        assertThat(maintenance.maintain().expired()).isOne();
        jdbc.update("update background_jobs set completed_at=clock_timestamp()-interval '31 days' where state='SKIPPED'");
        UUID active = insert(JobType.MEDIA_CLEANUP, null);
        UUID retry = insert(JobType.MEDIA_CLEANUP, null);
        jdbc.update("update background_jobs set state='RETRY_WAIT' where id=?", retry);
        UUID running = insert(JobType.MEDIA_CLEANUP, null);
        makeRunning(running);
        UUID recent = insert(JobType.MEDIA_CLEANUP, null);
        makeTerminal(recent);
        jdbc.update("update background_jobs set completed_at=clock_timestamp() where id=?", recent);
        jdbc.update("update background_jobs set created_at=clock_timestamp()-interval '90 days' where id in (?,?,?)", active, retry, running);
        assertThat(maintenance.maintain().terminalDeleted()).isEqualTo(500);
        assertThat(maintenance.maintain().terminalDeleted()).isOne();
        assertThat(row(active)).containsEntry("state", "READY");
        assertThat(row(retry)).containsEntry("state", "RETRY_WAIT");
        assertThat(row(running)).containsEntry("state", "RUNNING");
        assertThat(row(recent)).containsEntry("state", "SUCCEEDED");
    }
    @Test void runningSecretPurgeIsAlsoBoundedAtFiveHundred() {
        jdbc.update("""
                insert into background_jobs(id,queue,type,payload_version,dedupe_key,payload,max_attempts,
                    state,lease_token,lease_until,expires_at,secret_key_id,secret_nonce,secret_ciphertext)
                select md5('running-maintenance-'||n)::uuid,'MAIL_AUTH','PASSWORD_RESET_MAIL',1,
                    'running-maintenance-'||n,'{}'::jsonb,3,'RUNNING',md5('lease-'||n)::uuid,
                    clock_timestamp()+interval '1 hour',clock_timestamp()-interval '1 second',
                    'test-v1',decode(repeat('00',12),'hex'),decode(repeat('00',16),'hex')
                from generate_series(1,501) n
                """);
        assertThat(maintenance.maintain().runningSecretsPurged()).isEqualTo(500);
        assertThat(maintenance.maintain().runningSecretsPurged()).isOne();
        assertThat(jdbc.queryForObject("select count(*) from background_jobs where state='RUNNING' and lease_token is not null and secret_ciphertext is null", Integer.class)).isEqualTo(501);
    }
    private void makeRunning(UUID id) {
        jdbc.update("update background_jobs set state='RUNNING',lease_token=?,lease_until=clock_timestamp()+interval '1 hour' where id=?", UUID.randomUUID(), id);
    }
    private void makeTerminal(UUID id) {
        jdbc.update("update background_jobs set state='SUCCEEDED',completed_at=clock_timestamp()-interval '31 days',secret_key_id=null,secret_nonce=null,secret_ciphertext=null where id=?", id);
    }
    @Test void skipsLockedCandidate() throws Exception {
        UUID locked = insert(JobType.REGISTRATION_OTP_MAIL, Instant.now().minusSeconds(60));
        UUID other = insert(JobType.PASSWORD_RESET_MAIL, Instant.now().minusSeconds(60));
        UUID lockedRunning = insert(JobType.PASSWORD_RESET_MAIL, Instant.now().minusSeconds(60));
        UUID otherRunning = insert(JobType.PASSWORD_RESET_MAIL, Instant.now().minusSeconds(60));
        makeRunning(lockedRunning); makeRunning(otherRunning);
        UUID lockedTerminal = insert(JobType.MEDIA_CLEANUP, null);
        UUID otherTerminal = insert(JobType.MEDIA_CLEANUP, null);
        makeTerminal(lockedTerminal); makeTerminal(otherTerminal);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var holder = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(s -> {
                jdbc.queryForObject("select id from background_jobs where id=? for update", UUID.class, locked);
                jdbc.queryForObject("select id from background_jobs where id=? for update", UUID.class, lockedRunning);
                jdbc.queryForObject("select id from background_jobs where id=? for update", UUID.class, lockedTerminal);
                held.countDown();
                try { if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("lock timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            assertThat(held.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(maintenance.maintain()).isEqualTo(new JobMaintenanceService.Counts(1, 1, 1));
            purged(other, "SKIPPED"); assertThat(row(locked)).containsEntry("state", "READY");
            purged(otherRunning, "RUNNING"); assertThat(row(lockedRunning)).containsEntry("secret_key_id", "test-v1");
            assertThat(jdbc.queryForObject("select count(*) from background_jobs where id=?", Integer.class, otherTerminal)).isZero();
            assertThat(row(lockedTerminal)).containsEntry("state", "SUCCEEDED");
            release.countDown(); holder.get(3, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(3, TimeUnit.SECONDS)).isTrue(); }
    }
}
