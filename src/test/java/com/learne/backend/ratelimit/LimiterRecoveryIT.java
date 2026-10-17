package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Destructive memory configuration belongs exclusively to this test's own Redis. */
class LimiterRecoveryIT {
    private static final RateLimitPolicy POLICY =
            new RateLimitPolicy(RateLimitDimension.IP, "r1", 10, 1, Duration.ofSeconds(30));
    private LimiterRedisConnection transportToClose;

    @AfterEach
    void awaitTransportShutdown() throws Exception {
        if (transportToClose != null) LimiterTestCleanup.closeAndAwait(transportToClose);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noevictionOomPreservesStateAndFreshCallsRecoverAfterFixtureMemoryIsReleased(boolean expireFillers) throws Exception {
        try (var redis = new GenericContainer<>(DockerImageName.parse(
                "redis:7-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
                .withExposedPorts(6379)
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(128L * 1024 * 1024))
                .withCommand("redis-server", "--maxmemory", "64mb", "--maxmemory-policy", "noeviction")) {
            redis.start();
            var uri = RedisURI.Builder.redis(redis.getHost(), redis.getMappedPort(6379)).build();
            var client = RedisClient.create(uri);
            try (var observer = client.connect(); var transport = new LimiterRedisConnection(uri)) {
                transportToClose = transport;
                var commands = observer.sync();
                var store = new RedisTokenBucketStore(transport);
                String prefix = "task8:" + UUID.randomUUID() + ":";
                String bucket = prefix + "bucket", fresh = prefix + "fresh";
                assertThat(commands.configGet("maxmemory")).containsEntry("maxmemory", "67108864");
                assertThat(commands.configGet("maxmemory-policy")).containsEntry("maxmemory-policy", "noeviction");
                assertThat(store.consume(bucket, POLICY)).isEqualTo(RateLimitDecision.allowed()); // Warm Lua and connection.
                String saved = commands.get(bucket);
                long[] expiry = expiryBracket(commands, bucket);
                long evicted = stat(commands, "evicted_keys");
                commands.configSet("maxmemory", "4mb");
                var fillers = new ArrayList<String>();
                boolean oom = false;
                long fillDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                for (int i = 0; i < 128 && System.nanoTime() < fillDeadline; i++) {
                    String filler = prefix + "filler:" + i;
                    fillers.add(filler);
                    try {
                        commands.psetex(filler, 60000, "x".repeat(64 * 1024));
                    } catch (RedisCommandExecutionException failure) {
                        assertThat(failure.getMessage()).startsWith("OOM");
                        oom = true;
                        break;
                    }
                }
                assertThat(oom).as("real Redis write rejection within bounded fixture size/time").isTrue();
                // A large rejected SET may free its input buffer and leave room for a small SET.
                // Keep the retained fixture allocation definitely over the limit instead.
                commands.configSet("maxmemory", "3mb");
                assertThat(commands.info("memory").lines().filter(line -> line.startsWith("used_memory:"))
                        .mapToLong(line -> Long.parseLong(line.substring("used_memory:".length()).trim())).findFirst().orElseThrow())
                        .as("steady-state memory must remain above maxmemory for small limiter writes to fail")
                        .isGreaterThan(3L * 1024 * 1024);
                unavailable(() -> store.consume(bucket, POLICY));
                unavailable(() -> store.consume(fresh, POLICY));
                assertThat(commands.get(bucket)).isEqualTo(saved);
                long[] after = expiryBracket(commands, bucket);
                assertThat(after[0]).as("absolute expiry intervals overlap; no TTL renewal").isLessThanOrEqualTo(expiry[1]);
                assertThat(after[1]).isGreaterThanOrEqualTo(expiry[0]);
                assertThat(commands.strlen(bucket)).isLessThanOrEqualTo(512);
                assertThat(commands.exists(fresh)).isZero();
                assertThat(stat(commands, "evicted_keys")).isEqualTo(evicted);
                assertThat(commands.ping()).isEqualTo("PONG");

                // Only fixture keys are removed; no FLUSH and no shared-container CONFIG changes.
                if (expireFillers) {
                    for (String filler : fillers) commands.pexpire(filler, 1000);
                    await(() -> commands.exists(fillers.toArray(String[]::new)) == 0);
                    assertThat(commands.configGet("maxmemory")).containsEntry("maxmemory", "3145728");
                } else {
                    commands.del(fillers.toArray(String[]::new));
                }
                assertThat(commands.get(bucket)).as("memory relief does not auto-repair state").isEqualTo(saved);
                awaitAllowed(store, fresh);
                awaitAllowed(store, bucket);
                assertThat(commands.get(bucket)).isNotEqualTo(saved);
                assertThat(commands.pttl(fresh)).isPositive();
                assertThat(stat(commands, "evicted_keys")).isEqualTo(evicted);
                commands.configSet("maxmemory", "64mb");
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    void invalidStateIsNotRepairedButNaturalTtlExpiryAllowsANewConsume() throws Exception {
        // This separate instance also keeps the memory-pressure fixture entirely isolated.
        try (var redis = new GenericContainer<>(DockerImageName.parse(
                "redis:7-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
                .withExposedPorts(6379)
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(128L * 1024 * 1024))
                .withCommand("redis-server", "--maxmemory", "64mb", "--maxmemory-policy", "noeviction")) {
            redis.start();
            var uri = RedisURI.Builder.redis(redis.getHost(), redis.getMappedPort(6379)).build();
            var client = RedisClient.create(uri);
            try (var observer = client.connect(); var transport = new LimiterRedisConnection(uri)) {
                transportToClose = transport;
                String key = "task8:expiry:" + UUID.randomUUID();
                var store = new RedisTokenBucketStore(transport);
                assertThat(store.consume(key, POLICY)).isEqualTo(RateLimitDecision.allowed());
                long observationTtlMs = 10000; // Exceeds the 2.5-second call assertion with scheduling margin.
                observer.sync().psetex(key, observationTtlMs, "{");
                long[] before = expiryBracket(observer.sync(), key);
                // Separate creation from failure so renewing the same TTL is observably wrong.
                await(() -> redisTime(observer.sync()) >= before[1] - observationTtlMs + 500);
                unavailable(() -> store.consume(key, POLICY));
                assertThat(observer.sync().get(key)).isEqualTo("{");
                long[] after = expiryBracket(observer.sync(), key);
                assertThat(after[0]).as("failed consume must not renew absolute expiry").isLessThanOrEqualTo(before[1]);
                assertThat(after[1]).isGreaterThanOrEqualTo(before[0]);
                await(() -> observer.sync().exists(key) == 0, Duration.ofSeconds(12));
                awaitAllowed(store, key);
                assertThat(observer.sync().strlen(key)).isBetween(1L, 512L);
                assertThat(observer.sync().pttl(key)).isPositive();
            } finally {
                client.shutdown();
            }
        }
    }

    private static long redisTime(RedisCommands<String, String> commands) {
        var time = commands.time();
        return Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
    }

    private static long[] expiryBracket(RedisCommands<String, String> commands, String key) {
        long before = redisTime(commands);
        long ttl = commands.pttl(key);
        long after = redisTime(commands);
        assertThat(ttl).as("bucket must retain a finite positive TTL").isPositive();
        return new long[] {before + ttl, after + ttl};
    }

    private static long stat(RedisCommands<String, String> commands, String name) {
        return commands.info("stats").lines().filter(line -> line.startsWith(name + ":"))
                .mapToLong(line -> Long.parseLong(line.substring(name.length() + 1).trim())).findFirst().orElseThrow();
    }

    private static void unavailable(Runnable call) {
        long start = System.nanoTime();
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RateLimitException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(RateLimitException.Reason.UNAVAILABLE);
            assertThat(failure).hasNoCause().hasMessage("Rate limiting unavailable");
            assertThat(RateLimitErrorResponseFactory.response(failure).getStatusCode().value()).isEqualTo(503);
        });
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
    }

    private static void awaitAllowed(RedisTokenBucketStore store, String key) throws Exception {
        await(() -> {
            long start = System.nanoTime();
            try {
                assertThat(store.consume(key, POLICY)).isEqualTo(RateLimitDecision.allowed());
                return true;
            } catch (RateLimitException failure) {
                assertThat(failure.reason()).isEqualTo(RateLimitException.Reason.UNAVAILABLE);
                return false;
            } finally {
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
            }
        });
    }

    private static void await(BooleanSupplier condition) throws Exception {
        await(condition, Duration.ofSeconds(5));
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition within %s", timeout).isLessThan(deadline);
            Thread.sleep(25);
        }
    }
}
