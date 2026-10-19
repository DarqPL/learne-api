package com.learne.backend.jobs;

import static org.assertj.core.api.Assertions.*;

import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.JobEnqueueException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class JobEnqueueIT extends JobsPostgresTestSupport {
    private JobSpec spec(String key) {
        return new JobSpec(UUID.randomUUID(), QueueName.MEDIA, JobType.MEDIA_CLEANUP, 1, 0,
                key, "{}", null, null);
    }

    private UUID enqueue(JobSpec spec) {
        return new TransactionTemplate(manager).execute(status -> enqueuer.enqueue(spec));
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from background_jobs", Integer.class);
    }

    @Test
    void rejectsEnqueueOutsideTransaction() {
        assertThatThrownBy(() -> enqueuer.enqueue(spec("outside")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count()).isZero();
    }

    @Test
    void producerRollbackAlsoRollsBackJob() {
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            enqueuer.enqueue(spec("test:rollback"));
            throw new IllegalStateException("producer failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("producer failed");
        assertThat(count()).isZero();
    }

    @Test
    void duplicatePreservesOriginalIdentityPayloadSecretAndExpiryAndTransactionRemainsUsable() {
        byte[] nonce = new byte[12];
        byte[] bytes = {1, 2, 3};
        Instant expiry = Instant.parse("2030-01-01T00:00:00Z");
        var original = new JobSpec(UUID.randomUUID(), QueueName.MEDIA, JobType.MEDIA_CLEANUP,
                1, 0, "duplicate", "{\"original\":true}", expiry, new Ciphertext("key-1", nonce, bytes));
        assertThat(enqueue(original)).isEqualTo(original.id());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThat(enqueuer.enqueue(spec("duplicate"))).isEqualTo(original.id());
            assertThat(jdbc.queryForObject("select 42", Integer.class)).isEqualTo(42);
        });
        var row = jdbc.queryForMap("select * from background_jobs");
        assertThat(count()).isOne();
        assertThat(row).containsEntry("id", original.id()).containsEntry("secret_key_id", "key-1");
        assertThat(row.get("payload").toString()).isEqualTo("{\"original\": true}");
        assertThat((byte[]) row.get("secret_nonce")).containsExactly(nonce);
        assertThat((byte[]) row.get("secret_ciphertext")).containsExactly(bytes);
        assertThat(jdbc.queryForObject("select expires_at from background_jobs", java.sql.Timestamp.class).toInstant())
                .isEqualTo(expiry);
    }

    @Test
    void concurrentDedupeWaitsForWinnerAndReadsItsCommittedId() throws Exception {
        var inserted = new CountDownLatch(1);
        var releaseWinner = new CountDownLatch(1);
        var loserPid = new CompletableFuture<Integer>();
        var winnerSpec = spec("concurrent");
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> winner = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                UUID id = enqueuer.enqueue(winnerSpec);
                inserted.countDown();
                await(releaseWinner);
                return id;
            }));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<UUID> loser = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                loserPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                UUID id = enqueuer.enqueue(spec("concurrent"));
                assertThat(jdbc.queryForObject("select 42", Integer.class)).isEqualTo(42);
                return id;
            }));
            int pid = loserPid.get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean blocked = false;
            while (System.nanoTime() < deadline && !blocked) {
                blocked = Boolean.TRUE.equals(jdbc.queryForObject(
                        "select cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid));
            }
            assertThat(blocked).as("loser waits on winner's uncommitted dedupe key").isTrue();
            releaseWinner.countDown();
            assertThat(winner.get(5, TimeUnit.SECONDS)).isEqualTo(winnerSpec.id());
            assertThat(loser.get(5, TimeUnit.SECONDS)).isEqualTo(winnerSpec.id());
            assertThat(count()).isOne();
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void invalidEnvelopesAreRejectedBeforeWriting() {
        var good = spec("valid");
        JobSpec[] invalid = {
            null,
            new JobSpec(null, good.queue(), good.type(), 1, 0, "id", "{}", null, null),
            new JobSpec(good.id(), null, good.type(), 1, 0, "queue", "{}", null, null),
            new JobSpec(good.id(), good.queue(), null, 1, 0, "type", "{}", null, null),
            new JobSpec(good.id(), QueueName.AI, good.type(), 1, 0, "mapping", "{}", null, null),
            new JobSpec(good.id(), good.queue(), good.type(), 0, 0, "version", "{}", null, null),
            new JobSpec(good.id(), good.queue(), good.type(), 2, 0, "future-version", "{}", null, null),
            new JobSpec(good.id(), good.queue(), good.type(), 1, 1, "priority", "{}", null, null)
        };
        for (JobSpec job : invalid) {
            assertThatThrownBy(() -> enqueue(job)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String key : new String[]{null, "", " ", "x".repeat(256), "a\u0000b"}) {
            assertThatThrownBy(() -> enqueue(new JobSpec(good.id(), good.queue(), good.type(),
                    1, 0, key, "{}", null, null))).isInstanceOf(IllegalArgumentException.class);
        }
        for (String payload : new String[]{null, "", "{", "[]", "null", "42", "true", "\"text\"", "{} {}",
                "{\"x\":\"" + "é".repeat(32768) + "\"}"}) {
            assertThatThrownBy(() -> enqueue(new JobSpec(good.id(), good.queue(), good.type(),
                    1, 0, "payload", payload, null, null))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(count()).isZero();
    }

    @Test
    void acceptsExactUtf8PayloadAndCiphertextLimits() {
        String payload = "{\"x\":\"" + "é".repeat(32764) + "\"}";
        assertThat(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(65536);
        var job = new JobSpec(UUID.randomUUID(), QueueName.MEDIA, JobType.MEDIA_CLEANUP, 1, 0,
                "limits", payload, null, new Ciphertext("key", new byte[12], new byte[4112]));
        assertThat(enqueue(job)).isEqualTo(job.id());
        assertThat(jdbc.queryForObject("select payload->>'x' from background_jobs", String.class))
                .isEqualTo("é".repeat(32764));
        assertThat(jdbc.queryForObject("select octet_length(secret_ciphertext) from background_jobs", Integer.class))
                .isEqualTo(4112);
    }

    @Test
    void ciphertextIsValidatedImmutableAndRedacted() {
        for (String key : new String[]{null, "", " ", "x".repeat(65), "a\u0000b"}) {
            assertThatThrownBy(() -> new Ciphertext(key, new byte[12], new byte[16]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Ciphertext("key", null, new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ciphertext("key", new byte[11], new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ciphertext("key", new byte[12], null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ciphertext("key", new byte[12], new byte[4113])).isInstanceOf(IllegalArgumentException.class);
        byte[] nonce = new byte[12];
        byte[] bytes = {7, 8, 9};
        var secret = new Ciphertext("key", nonce, bytes);
        nonce[0] = 1;
        bytes[0] = 1;
        secret.nonce()[0] = 2;
        secret.bytes()[0] = 2;
        assertThat(secret.nonce()).containsExactly(new byte[12]);
        assertThat(secret.bytes()).containsExactly(7, 8, 9);
        assertThat(secret.toString()).doesNotContain("7", "8", "9");
    }

    @Test
    void persistenceFailureRollsBackProducerAndIsNotAnApiException() {
        var original = spec("original");
        enqueue(original);
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            jdbc.update("update background_jobs set priority=7 where id=?", original.id());
            enqueuer.enqueue(new JobSpec(original.id(), original.queue(), original.type(), 1, 0,
                    "different-key", "{}", null, null));
            return null;
        })).isExactlyInstanceOf(JobEnqueueException.class).hasMessage("QUEUE_WRITE_UNAVAILABLE")
                .hasNoCause();
        assertThat(jdbc.queryForObject("select priority from background_jobs where id=?", Integer.class, original.id())).isZero();
        assertThat(count()).isOne();
    }

    @Test
    void nativeJsonbFailureIsSafeToLogAndStillRollsBackProducer() {
        var original = spec("before-jsonb-failure");
        enqueue(original);
        String marker = "PRIVATE_NATIVE_PAYLOAD_b637e1";
        String payload = "{\"x\":\"" + marker + "\\u0000\"}";
        Throwable failure = catchThrowable(() -> new TransactionTemplate(manager).execute(status -> {
            jdbc.update("update background_jobs set priority=7 where id=?", original.id());
            enqueuer.enqueue(new JobSpec(UUID.randomUUID(), QueueName.MEDIA, JobType.MEDIA_CLEANUP,
                    1, 0, "jsonb-failure", payload, null, null));
            return null;
        }));
        assertThat(failure).isExactlyInstanceOf(JobEnqueueException.class)
                .hasMessage("QUEUE_WRITE_UNAVAILABLE");
        var printed = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(printed));
        assertThat(printed.toString()).doesNotContain(marker);
        assertThat(failure.toString()).doesNotContain(marker);
        assertThat(failure.getMessage()).doesNotContain(marker);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(jdbc.queryForObject("select priority from background_jobs where id=?", Integer.class, original.id())).isZero();
        assertThat(count()).isOne();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test transaction");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
