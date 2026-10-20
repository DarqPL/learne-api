package com.learne.backend.jobs;

import static org.assertj.core.api.Assertions.*;

import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.JobRecoveryService;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(10)
class JobLeaseIT extends JobsPostgresTestSupport {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Map<JobType, Set<Integer>> MEDIA = Map.of(JobType.MEDIA_CLEANUP, Set.of(1));
    @Autowired JobRecoveryService recovery;

    private UUID insert() {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(manager).executeWithoutResult(s -> enqueuer.enqueue(new JobSpec(id,
                QueueName.MEDIA, JobType.MEDIA_CLEANUP, 1, 0, id.toString(), "{\"x\":true}", null,
                new Ciphertext("test-key", new byte[12], new byte[]{1, 2, 3}))));
        return id;
    }

    private JobLease claim() {
        var claimed = store.claim(QueueName.MEDIA, MEDIA, LEASE);
        assertThat(claimed).as("due supported job must be leased").isPresent();
        return claimed.orElseThrow();
    }
    private Map<String, Object> row(UUID id) { return jdbc.queryForMap("select * from background_jobs where id=?", id); }
    private void expire(UUID id) {
        jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second' where id=?", id);
    }

    @Test
    @Timeout(15)
    void heartbeatWaitingOnLockedRowHasFiniteJdbcTimeout() throws Exception {
        UUID id = insert();
        JobLease lease = claim();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.queryForObject("select id from background_jobs where id=? for update", UUID.class, id);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Fixture not released");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
            var renewal = pool.submit(() -> store.heartbeat(lease, LEASE));
            assertThatThrownBy(() -> renewal.get(8, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
            release.countDown();
            holder.get(2, TimeUnit.SECONDS);
            assertThat(store.isLeaseCurrent(lease)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void retryRequiresAvailabilityStrictlyBeforeDurableExpiry() {
        Instant expiry = Instant.parse("2035-01-01T00:00:00Z");
        for (long offset : new long[]{-1, 0, 1}) {
            UUID id = insert();
            JobLease lease = claim();
            jdbc.update("update background_jobs set expires_at=? where id=?",
                    expiry.atOffset(java.time.ZoneOffset.UTC), id);
            var before = row(id);
            assertThat(store.retry(lease, expiry.plusSeconds(offset), "BUSY"))
                    .as("availability offset %s seconds from expiry", offset).isEqualTo(offset < 0);
            if (offset < 0) {
                assertThat(row(id)).containsEntry("state", "RETRY_WAIT").containsEntry("lease_token", null);
                assertThat(jdbc.queryForObject("select available_at from background_jobs where id=?",
                        java.sql.Timestamp.class, id).toInstant()).isEqualTo(expiry.minusSeconds(1));
            } else {
                assertThat(row(id)).usingRecursiveComparison().isEqualTo(before);
                assertThat(store.isLeaseCurrent(lease)).isTrue();
                assertThat(store.complete(lease, JobState.SKIPPED, "JOB_EXPIRED")).isTrue();
            }
        }
    }

    @Test
    void exactSupportedPairsQueueDueTimeAndStablePriorityOrdering() {
        UUID later = insert(), first = insert(), version = insert(), type = insert(), queue = insert(), future = insert();
        jdbc.update("update background_jobs set available_at='2020-01-01',created_at='2020-01-01'");
        jdbc.update("update background_jobs set priority=1 where id=?", later);
        jdbc.update("update background_jobs set payload_version=2 where id=?", version);
        jdbc.update("update background_jobs set type='FUTURE_TYPE' where id=?", type);
        jdbc.update("update background_jobs set queue='AI' where id=?", queue);
        jdbc.update("update background_jobs set available_at=clock_timestamp()+interval '1 hour' where id=?", future);
        assertThat(store.claim(QueueName.MEDIA, Map.of(), LEASE)).isEmpty();
        assertThat(claim().jobId()).isEqualTo(first);
        assertThat(claim().jobId()).isEqualTo(later);
        assertThat(store.claim(QueueName.MEDIA, MEDIA, LEASE)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from background_jobs where state='READY'", Integer.class)).isEqualTo(4);
    }

    @Test
    void capabilityVersionsStayPairedAndInvitationRunsAfterAuthMailWithStableTies() {
        UUID invitation = insert(), otp = insert(), reset = insert(), unsupported = insert();
        jdbc.update("update background_jobs set queue='MAIL_AUTH',available_at='2020-01-01',created_at='2020-01-01'");
        jdbc.update("update background_jobs set type='TEACHER_INVITATION_MAIL',priority=1 where id=?", invitation);
        jdbc.update("update background_jobs set type='REGISTRATION_OTP_MAIL' where id=?", otp);
        jdbc.update("update background_jobs set type='PASSWORD_RESET_MAIL',payload_version=2 where id=?", reset);
        jdbc.update("update background_jobs set type='REGISTRATION_OTP_MAIL',payload_version=2 where id=?", unsupported);
        var capabilities = Map.of(JobType.REGISTRATION_OTP_MAIL, Set.of(1),
                JobType.PASSWORD_RESET_MAIL, Set.of(2), JobType.TEACHER_INVITATION_MAIL, Set.of(1));
        var expected = jdbc.queryForList("select id from background_jobs where id<>? order by priority,available_at,created_at,id", UUID.class, unsupported);
        for (UUID id : expected)
            assertThat(store.claim(QueueName.MAIL_AUTH, capabilities, LEASE).orElseThrow().jobId()).isEqualTo(id);
        assertThat(store.claim(QueueName.MAIL_AUTH, capabilities, LEASE)).isEmpty();
        assertThat(row(unsupported)).containsEntry("state", "READY");
    }

    @Test
    void recoveryHonorsMaximumBatchWithMoreThanFiveHundredExpiredRows() {
        jdbc.update("""
                insert into background_jobs(id,queue,type,payload_version,dedupe_key,payload,max_attempts,
                    state,attempts,lease_token,lease_until)
                select md5('job-'||n)::uuid,'MEDIA','MEDIA_CLEANUP',1,'batch-'||n,'{}'::jsonb,3,
                    'RUNNING',3,md5('token-'||n)::uuid,clock_timestamp()-interval '1 second'
                from generate_series(1,501) n
                """);
        assertThat(recovery.recoverExpired(500)).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs where state='RUNNING'", Integer.class)).isOne();
        assertThat(recovery.recoverExpired(500)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from background_jobs where state='READY' and attempts=3", Integer.class)).isEqualTo(501);
    }

    @Test
    void simultaneousClaimersSkipLockedCandidateAndOwnDistinctRows() throws Exception {
        UUID locked = insert();
        insert(); insert();
        jdbc.update("update background_jobs set priority=-1 where id=?", locked);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(s -> {
                jdbc.execute("set local lock_timeout='3s'");
                jdbc.queryForObject("select id from background_jobs where id=? for update", UUID.class, locked);
                held.countDown(); await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var a = pool.submit(() -> { await(start); return claim(); });
            var b = pool.submit(() -> { await(start); return claim(); });
            start.countDown();
            JobLease one = a.get(5, TimeUnit.SECONDS), two = b.get(5, TimeUnit.SECONDS);
            assertThat(one.jobId()).isNotEqualTo(locked).isNotEqualTo(two.jobId());
            assertThat(two.jobId()).isNotEqualTo(locked);
            release.countDown(); holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown(); start.countDown(); pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void expiredAndReplacedTokensFenceEveryMutationAndNeverRevive() {
        UUID id = insert(); JobLease old = claim(); expire(id);
        rejected(old);
        assertThat(recovery.recoverExpired(1)).isOne();
        JobLease current = claim();
        assertThat(current.token()).isNotEqualTo(old.token());
        rejected(old);
        assertThat(store.heartbeat(current, LEASE)).isTrue();
        assertThat(store.complete(current, JobState.SUCCEEDED, null)).isTrue();
        rejected(current);
    }

    private void rejected(JobLease lease) {
        assertThat(store.isLeaseCurrent(lease)).isFalse();
        assertThat(store.heartbeat(lease, LEASE)).isFalse();
        assertThat(store.complete(lease, JobState.DEAD, "FAILED")).isFalse();
        assertThat(store.retry(lease, Instant.now(), "RETRY")).isFalse();
        assertThat(store.yield(lease, Instant.now(), true)).isFalse();
    }

    @Test
    void currentOwnershipUsesRenewedDatabaseLeaseAndRejectsTokenMismatch() {
        UUID id = insert();
        JobLease lease = claim();
        var staleSnapshot = new JobLease(lease.jobId(), lease.token(), Instant.EPOCH, lease.type(),
                lease.payloadVersion(), lease.attempt(), lease.maxAttempts(), lease.payloadJson(), lease.secret(), false);
        assertThat(store.isLeaseCurrent(staleSnapshot)).isTrue();
        assertThat(store.heartbeat(staleSnapshot, LEASE)).isTrue();
        assertThat(store.isLeaseCurrent(staleSnapshot)).isTrue();
        jdbc.update("update background_jobs set lease_token=? where id=?", UUID.randomUUID(), id);
        assertThat(store.isLeaseCurrent(staleSnapshot)).isFalse();
    }

    @Test
    void exhaustionProducesControlLeaseOnlyAfterLastPermittedAttempt() {
        UUID id = insert();
        jdbc.update("update background_jobs set max_attempts=3 where id=?", id);
        for (int attempt = 1; attempt <= 3; attempt++) {
            JobLease lease = claim();
            assertThat(lease.attempt()).isEqualTo(attempt);
            assertThat(lease.terminalizeOnly()).isFalse();
            assertThat(lease.payloadJson()).contains("x");
            assertThat(lease.secret().bytes()).containsExactly(1, 2, 3);
            expire(id);
            assertThat(recovery.recoverExpired(1)).isOne();
            assertThat(row(id)).containsEntry("state", "READY").containsEntry("attempts", attempt)
                    .containsEntry("lease_token", null).containsEntry("lease_until", null).containsEntry("secret_key_id", "test-key");
            assertThat(jdbc.queryForObject("select abs(extract(epoch from available_at-clock_timestamp()))<2 from background_jobs where id=?", Boolean.class, id)).isTrue();
        }
        JobLease control = claim();
        assertThat(control.attempt()).isEqualTo(3);
        assertThat(control.terminalizeOnly()).isTrue();
        assertThat(store.complete(control, JobState.DEAD, "EXHAUSTED")).isTrue();
    }

    @Test
    void terminalizationErasesSecretsAndRetryYieldPreserveThemAndResetOnlyExplicitly() {
        for (JobState terminal : List.of(JobState.SUCCEEDED, JobState.SKIPPED, JobState.DEAD)) {
            UUID id = insert(); JobLease lease = claim();
            assertThat(store.complete(lease, terminal, "SAFE_CODE")).isTrue();
            assertThat(row(id)).containsEntry("state", terminal.name()).containsEntry("lease_token", null)
                    .containsEntry("lease_until", null).containsEntry("secret_key_id", null)
                    .containsEntry("secret_nonce", null).containsEntry("secret_ciphertext", null);
            assertThat(row(id).get("completed_at")).isNotNull();
        }
        UUID id = insert(); JobLease lease = claim();
        assertThat(store.retry(lease, Instant.EPOCH, "TEMPORARY")).isTrue();
        assertThat(row(id)).containsEntry("state", "RETRY_WAIT").containsEntry("attempts", 1)
                .containsEntry("secret_key_id", "test-key").containsEntry("lease_token", null).containsEntry("lease_until", null);
        assertThat(store.yield(claim(), Instant.EPOCH, false)).isTrue();
        assertThat(row(id)).containsEntry("state", "READY").containsEntry("attempts", 2);
        assertThat(store.yield(claim(), Instant.EPOCH, true)).isTrue();
        assertThat(row(id)).containsEntry("attempts", 0).containsEntry("secret_key_id", "test-key")
                .containsEntry("lease_token", null).containsEntry("lease_until", null);
        Instant future = Instant.parse("2035-01-01T00:00:00Z");
        assertThat(store.retry(claim(), future, "WAIT")).isTrue();
        assertThat(store.claim(QueueName.MEDIA, MEDIA, LEASE)).isEmpty();
        assertThat(jdbc.queryForObject("select available_at from background_jobs where id=?", java.sql.Timestamp.class, id).toInstant()).isEqualTo(future);
    }

    @Test
    void statementClockNotTransactionStartAndHeartbeatUsesDatabaseRatherThanSnapshot() {
        UUID id = insert();
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            jdbc.execute("select pg_sleep(0.15)");
            JobLease lease = claim();
            assertThat(jdbc.queryForObject("select extract(epoch from updated_at-transaction_timestamp())>0.1 from background_jobs where id=?", Boolean.class, id)).isTrue();
            jdbc.update("update background_jobs set lease_until=clock_timestamp()+interval '1 hour' where id=?", id);
            assertThat(store.heartbeat(lease, Duration.ofSeconds(5))).isTrue();
            assertThat(jdbc.queryForObject("select extract(epoch from lease_until-clock_timestamp()) between 4 and 6 from background_jobs where id=?", Boolean.class, id)).isTrue();
            assertThat(jdbc.queryForObject("select extract(epoch from updated_at-transaction_timestamp())>0.1 from background_jobs where id=?", Boolean.class, id)).isTrue();
        });
    }

    @Test
    void callerCheckpointRollbackIncludesEveryStoreOperation() {
        UUID id = insert();
        new TransactionTemplate(manager).executeWithoutResult(s -> { claim(); s.setRollbackOnly(); });
        assertThat(row(id)).containsEntry("state", "READY").containsEntry("attempts", 0);
        JobLease lease = claim();
        for (int operation = 0; operation < 4; operation++) {
            final int op = operation;
            var before = row(id);
            new TransactionTemplate(manager).executeWithoutResult(s -> {
                jdbc.update("update background_jobs set priority=9 where id=?", id);
                boolean changed = switch (op) {
                    case 0 -> store.heartbeat(lease, Duration.ofMinutes(2));
                    case 1 -> store.complete(lease, JobState.SUCCEEDED, null);
                    case 2 -> store.retry(lease, Instant.EPOCH, "RETRY");
                    default -> store.yield(lease, Instant.EPOCH, true);
                };
                assertThat(changed).isTrue();
                s.setRollbackOnly();
            });
            assertThat(row(id)).containsEntry("state", "RUNNING").containsEntry("priority", before.get("priority"))
                    .containsEntry("lease_until", before.get("lease_until")).containsEntry("secret_key_id", "test-key");
        }
        expire(id);
        new TransactionTemplate(manager).executeWithoutResult(s -> { assertThat(recovery.recoverExpired(1)).isOne(); s.setRollbackOnly(); });
        assertThat(row(id)).containsEntry("state", "RUNNING");
    }

    @Test
    void failedStaleCompletionRollsBackEarlierBusinessCheckpointAndPreservesNewLease() {
        Long userId = jdbc.queryForObject("""
                insert into users(email,password_hash,full_name,role)
                values(?, 'fixture-only-hash', 'Original checkpoint', 'STUDENT') returning id
                """, Long.class, UUID.randomUUID() + "@example.invalid");
        UUID jobId = insert();
        JobLease stale = claim();
        expire(jobId);
        assertThat(recovery.recoverExpired(1)).isOne();
        JobLease current = claim();
        assertThat(current.token()).isNotEqualTo(stale.token());
        var before = row(jobId);
        var checkpoint = new TransactionTemplate(manager);
        checkpoint.setTimeout(5);

        assertThatThrownBy(() -> checkpoint.executeWithoutResult(status -> {
            assertThat(jdbc.update("update users set full_name=? where id=?",
                    "Uncommitted checkpoint", userId)).isOne();
            assertThat(jdbc.queryForObject("select full_name from users where id=?", String.class, userId))
                    .isEqualTo("Uncommitted checkpoint");
            boolean completed = store.complete(stale, JobState.SUCCEEDED, null);
            assertThat(completed).as("stale completion must fail its ownership fence").isFalse();
            // Caller protocol: a failed fence aborts the preceding business checkpoint too.
            if (!completed) throw new IllegalStateException("CHECKPOINT_LEASE_LOST");
        })).isExactlyInstanceOf(IllegalStateException.class).hasMessage("CHECKPOINT_LEASE_LOST");

        assertThat(jdbc.queryForObject("select full_name from users where id=?", String.class, userId))
                .isEqualTo("Original checkpoint");
        assertThat(row(jobId)).usingRecursiveComparison().isEqualTo(before);
        assertThat(row(jobId)).containsEntry("state", "RUNNING").containsEntry("lease_token", current.token());
    }

    @Test
    void boundedRecoverySkipsLockedExpiredAndLeavesActiveJobsUntouched() throws Exception {
        UUID locked = insert(); JobLease lockedLease = claim(); expire(locked);
        UUID other = insert(); claim(); expire(other);
        UUID active = insert(); claim();
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var holder = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(s -> {
                jdbc.queryForObject("select id from background_jobs where id=? for update", UUID.class, lockedLease.jobId());
                held.countDown(); await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(recovery.recoverExpired(1)).isOne();
            assertThat(row(other)).containsEntry("state", "READY");
            assertThat(row(active)).containsEntry("state", "RUNNING");
            release.countDown(); holder.get(5, TimeUnit.SECONDS);
            assertThat(recovery.recoverExpired(500)).isOne();
            assertThat(recovery.recoverExpired(500)).isZero();
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void rejectsInvalidDurationsStatesLimitsAndCodesSafely() {
        insert(); JobLease lease = claim();
        for (Duration duration : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> store.claim(QueueName.MEDIA, MEDIA, duration)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.heartbeat(lease, duration)).isInstanceOf(IllegalArgumentException.class);
        }
        for (JobState state : new JobState[]{null, JobState.READY, JobState.RUNNING, JobState.RETRY_WAIT})
            assertThatThrownBy(() -> store.complete(lease, state, null)).isInstanceOf(IllegalArgumentException.class);
        for (int limit : new int[]{-1, 0, 501})
            assertThatThrownBy(() -> recovery.recoverExpired(limit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.complete(lease, JobState.DEAD, "private diagnostic data"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private diagnostic data");
        assertThatThrownBy(() -> store.retry(lease, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.yield(lease, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.claim(null, MEDIA, LEASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.claim(QueueName.MEDIA, null, LEASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.claim(QueueName.MEDIA, Map.of(JobType.MEDIA_CLEANUP, Set.of(0)), LEASE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.claim(QueueName.MEDIA, Map.of(JobType.MEDIA_CLEANUP, Set.of()), LEASE)).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
