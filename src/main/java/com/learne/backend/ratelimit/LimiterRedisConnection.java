package com.learne.backend.ratelimit;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Dedicated, multiplexed limiter transport. A failed logical call is never replayed.
 * A command already handed to Redis may still consume once after an unavailable result;
 * cancellation is not a refund or an exactly-once guarantee.
 * Intended to be a singleton; it never borrows the application's cache connection/client.
 */
public final class LimiterRedisConnection implements RedisTokenBucketStore.ScriptExecutor, AutoCloseable {
    private static final long BUDGET = TimeUnit.SECONDS.toNanos(2);
    private static final long COOLDOWN = TimeUnit.SECONDS.toNanos(1);
    private static final Semaphore OPERATIONS = new Semaphore(64);
    private static final Semaphore CONNECTIONS = new Semaphore(1);
    private final LongSupplier ticker;
    private final Connector connector;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Generation> current = new AtomicReference<>();
    private volatile Long retryAt;

    /** The abort completion acknowledges closure, not merely cancellation of the result. */
    interface ConnectAttempt {
        CompletableFuture<StatefulRedisConnection<String, String>> future();
        CompletableFuture<Void> abort();
    }
    interface Connector extends AutoCloseable {
        ConnectAttempt connect();
        void close();
    }
    public LimiterRedisConnection(RedisURI uri) {
        this(System::nanoTime, new LettuceLimiterConnector(Objects.requireNonNull(uri)));
    }

    LimiterRedisConnection(LongSupplier ticker, Connector connector) {
        this.ticker = Objects.requireNonNull(ticker);
        this.connector = Objects.requireNonNull(connector);
    }

    @Override
    public List<?> execute(String sha, String source, String key, List<String> args) {
        long deadline = ticker.getAsLong() + BUDGET;
        if (closed.get() || !OPERATIONS.tryAcquire()) throw RateLimitException.unavailable();
        Generation generation = null;
        try {
            List<String> immutableArgs = List.copyOf(args);
            generation = acquire(deadline);
            var connection = generation.connection.get();
            if (connection == null || !connection.isOpen() || generation.retired.get() || closed.get()) {
                throw RateLimitException.unavailable();
            }
            try {
                return command(generation, deadline, () -> connection.async().evalsha(sha,
                        ScriptOutputType.MULTI, new String[] {key}, immutableArgs.toArray(String[]::new)));
            } catch (ExecutionException failure) {
                // Never infer NOSCRIPT from text, and never retry ambiguous failures.
                if (!(failure.getCause() instanceof RedisNoScriptException)) throw failure;
                return command(generation, deadline, () -> connection.async().eval(source,
                        ScriptOutputType.MULTI, new String[] {key}, immutableArgs.toArray(String[]::new)));
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            if (generation != null) retire(generation, false);
            throw RateLimitException.unavailable();
        } catch (Exception failure) {
            if (generation != null) retire(generation, false);
            // No causes, URIs, credentials, identifiers, or script arguments escape this boundary.
            throw RateLimitException.unavailable();
        } finally {
            OPERATIONS.release();
        }
    }

    private Generation acquire(long deadline) throws Exception {
        remaining(deadline);
        if (closed.get()) throw RateLimitException.unavailable();
        Generation existing = current.get();
        if (existing != null) {
            if (existing.retired.get() || existing.connection.get() == null) throw RateLimitException.unavailable();
            return existing;
        }
        Long retry = retryAt;
        if (retry != null && ticker.getAsLong() - retry < 0) throw RateLimitException.unavailable();
        if (!CONNECTIONS.tryAcquire()) throw RateLimitException.unavailable();
        var generation = new Generation();
        if (!current.compareAndSet(null, generation)) {
            generation.releaseLease();
            throw RateLimitException.unavailable();
        }
        try {
            try {
                if (closed.get()) throw RateLimitException.unavailable();
                generation.attempt = connector.connect();
                generation.attempt.future().whenComplete((connection, failure) -> {
                    if (connection != null) {
                        connection.setAutoFlushCommands(true);
                        generation.connection.set(connection);
                        if (generation.retired.get() || closed.get()) closeConnection(generation, connection);
                        generation.initialized.complete(null);
                    } else {
                        generation.initialized.completeExceptionally(RateLimitException.unavailable());
                    }
                });
            } finally {
                // Shutdown may retire a generation while connectAsync is still being initiated.
                // Keep its JVM lease until the attempt exists and can acknowledge actual closure.
                generation.initiated.complete(null);
            }
            if (generation.retired.get() || closed.get()) throw RateLimitException.unavailable();
            generation.attempt.future().get(remaining(deadline), TimeUnit.NANOSECONDS);
            generation.initialized.get(remaining(deadline), TimeUnit.NANOSECONDS);
            remaining(deadline);
            if (generation.retired.get() || closed.get()) throw RateLimitException.unavailable();
            return generation;
        } catch (Exception failure) {
            retire(generation, true);
            throw failure;
        }
    }

    private List<?> command(Generation generation, long deadline, Supplier<Future<List<?>>> send)
            throws Exception {
        remaining(deadline);
        if (closed.get() || generation.retired.get()) throw RateLimitException.unavailable();
        Future<List<?>> future = send.get();
        generation.pending.add(future);
        try {
            if (generation.retired.get() || closed.get()) {
                future.cancel(true);
                throw RateLimitException.unavailable();
            }
            List<?> result = future.get(remaining(deadline), TimeUnit.NANOSECONDS);
            remaining(deadline);
            return result;
        } catch (ExecutionException failure) {
            if (!(failure.getCause() instanceof RedisNoScriptException)) future.cancel(true);
            throw failure;
        } catch (Exception failure) {
            future.cancel(true);
            throw failure;
        } finally {
            generation.pending.remove(future);
        }
    }

    private long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - ticker.getAsLong();
        if (remaining <= 0) throw new TimeoutException();
        return remaining;
    }

    private void retire(Generation generation, boolean connectFailed) {
        if (!generation.retired.compareAndSet(false, true)) return;
        if (connectFailed) retryAt = ticker.getAsLong() + COOLDOWN;
        generation.initialized.cancel(true);
        for (Future<?> pending : generation.pending) pending.cancel(true);
        generation.initiated.whenComplete((ignored, failure) -> cleanUp(generation));
    }

    private void cleanUp(Generation generation) {
        var connection = generation.connection.get();
        if (connection != null) closeConnection(generation, connection);
        else if (generation.attempt != null) {
            generation.attempt.abort().whenComplete((ignored, failure) -> {
                if (failure == null) release(generation);
            });
        } else release(generation);
    }

    private void closeConnection(Generation generation, StatefulRedisConnection<String, String> connection) {
        if (!generation.closing.compareAndSet(false, true)) return;
        // A late callback owns only its own generation. A failed close keeps admission shut.
        connection.closeAsync().whenComplete((ignored, failure) -> {
            if (failure == null) release(generation);
        });
    }

    private void release(Generation generation) {
        current.compareAndSet(generation, null);
        generation.releaseLease();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Generation generation = current.get();
        if (generation != null) retire(generation, false);
        connector.close();
    }

    private static final class Generation {
        final AtomicBoolean retired = new AtomicBoolean();
        final AtomicBoolean closing = new AtomicBoolean();
        final AtomicBoolean leaseHeld = new AtomicBoolean(true);
        final CompletableFuture<Void> initiated = new CompletableFuture<>();
        final CompletableFuture<Void> initialized = new CompletableFuture<>();
        final AtomicReference<StatefulRedisConnection<String, String>> connection = new AtomicReference<>();
        final Set<Future<?>> pending = ConcurrentHashMap.newKeySet();
        volatile ConnectAttempt attempt;

        void releaseLease() {
            if (leaseHeld.compareAndSet(true, false)) CONNECTIONS.release();
        }
    }
}
