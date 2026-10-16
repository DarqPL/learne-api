package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedisTokenBucketIT extends RedisTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SNAPSHOT_LUA = """
            local function snapshot()
                local before = redis.call('TIME')
                local raw = redis.call('GET', KEYS[1])
                local ttl = redis.call('PTTL', KEYS[1])
                local after = redis.call('TIME')
                return {before, after, raw or '', ttl}
            end
            """;

    private static RateLimitPolicy policy(long capacity, long refill, long period) {
        return new RateLimitPolicy(RateLimitDimension.IP, "r1", capacity, refill, Duration.ofMillis(period));
    }

    private static String key() {
        return "task4:" + UUID.randomUUID();
    }

    private static RedisTokenBucketStore store(RedisCommands<String, String> commands) {
        // Task 4 intentionally exercises EVAL directly. Task 5 owns EVALSHA/NOSCRIPT retry.
        return new RedisTokenBucketStore((sha, source, key, args) -> commands.eval(
                source, ScriptOutputType.MULTI, new String[] {key}, args.toArray(String[]::new)));
    }

    private static long now(RedisCommands<String, String> commands) {
        List<String> time = commands.time();
        return milliseconds(time);
    }

    private static long milliseconds(List<?> time) {
        return Long.parseLong(time.get(0).toString()) * 1000 + Long.parseLong(time.get(1).toString()) / 1000;
    }

    private record BucketSnapshot(long beforeMs, long afterMs, String raw, long ttl) {
        static BucketSnapshot decode(List<?> values) {
            return new BucketSnapshot(milliseconds((List<?>) values.get(0)),
                    milliseconds((List<?>) values.get(1)), (String) values.get(2), (Long) values.get(3));
        }

        void assertExpiresAt(long expiry) {
            assertThat(ttl).isGreaterThanOrEqualTo(0);
            assertThat(expiry).as("Redis expiry bracket from TIME + PTTL")
                    .isBetween(beforeMs + ttl, afterMs + ttl);
        }
    }

    private record BucketExecution(BucketSnapshot before, BucketSnapshot after) {
        void assertUnchanged() {
            assertThat(after.raw()).isEqualTo(before.raw());
            assertThat(after.ttl()).isBetween(0L, before.ttl());
            // The two Redis TIME/PTTL expiry intervals must overlap, even across a millisecond tick.
            assertThat(after.beforeMs() + after.ttl()).isLessThanOrEqualTo(before.afterMs() + before.ttl());
            assertThat(after.afterMs() + after.ttl()).isGreaterThanOrEqualTo(before.beforeMs() + before.ttl());
        }
    }

    private static RedisTokenBucketStore observedStore(RedisCommands<String, String> commands,
            List<BucketExecution> executions) {
        return new RedisTokenBucketStore((sha, source, key, args) -> {
            // Test-only observations surround the unmodified production script in one EVAL.
            // No JVM pause can expire the bucket between execution and state/TTL inspection.
            String observed = SNAPSHOT_LUA + """
                    local before = snapshot()
                    local ok, decision = pcall(function()
                    """ + source + "\nend)\nreturn {ok and 1 or 0, ok and decision or {}, before, snapshot()}";
            List<?> result = commands.eval(observed, ScriptOutputType.MULTI,
                    new String[] {key}, args.toArray(String[]::new));
            executions.add(new BucketExecution(BucketSnapshot.decode((List<?>) result.get(2)),
                    BucketSnapshot.decode((List<?>) result.get(3))));
            if (!Long.valueOf(1).equals(result.get(0))) throw RateLimitException.unavailable();
            return (List<?>) result.get(1);
        });
    }

    private static BucketSnapshot snapshot(RedisCommands<String, String> commands, String key) {
        return BucketSnapshot.decode(commands.eval(SNAPSHOT_LUA + "return snapshot()",
                ScriptOutputType.MULTI, new String[] {key}));
    }

    private static RateLimitKeyFactory lifecycleFactory(String secret) {
        return new RateLimitKeyFactory(new RateLimitProperties(
                true, "task4-lifecycle", "test", secret, null, null, null));
    }

    private static String lifecycleKey(RateLimitKeyFactory factory, String policyId, RateLimitPolicy policy) {
        return factory.key(policyId, policy, policy.dimension(), "192.0.2.42");
    }

    private static void assertRetainedDebit(RateLimitPolicy policy, BucketExecution execution) throws Exception {
        JsonNode before = JSON.readTree(execution.before().raw());
        JsonNode after = JSON.readTree(execution.after().raw());
        long last = after.get("updatedAtMs").longValue();
        assertThat(last).isBetween(execution.before().beforeMs(), execution.after().afterMs());
        assertThat(last).isGreaterThanOrEqualTo(before.get("updatedAtMs").longValue());
        long elapsed = last - before.get("updatedAtMs").longValue();
        long available = Math.min(policy.capacityCredit(), before.get("credit").longValue()
                + elapsed * policy.refillTokens());
        assertThat(after.get("credit").longValue()).as("debit retained credit, not a fresh/merged budget")
                .isEqualTo(available - policy.periodMs());
        assertThat(after.get("fingerprint").textValue()).isEqualTo(policy.fingerprint());
    }

    private static String state(RateLimitPolicy policy, long credit, long updated) {
        return "{\"schema\":1,\"fingerprint\":\"" + policy.fingerprint()
                + "\",\"credit\":" + credit + ",\"updatedAtMs\":" + updated + "}";
    }

    private static JsonNode state(RedisCommands<String, String> commands, String key) throws Exception {
        return JSON.readTree(commands.get(key));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition within five seconds").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    @Test
    void respondsToPing() {
        try (var connection = redisClient.connect()) {
            assertThat(connection.sync().ping()).isEqualTo("PONG");
        }
    }

    @Test
    void usesBoundedMemoryWithoutEviction() {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            assertThat(commands.configGet("maxmemory"))
                    .containsEntry("maxmemory", Long.toString(64L * 1024 * 1024));
            assertThat(commands.configGet("maxmemory-policy"))
                    .containsEntry("maxmemory-policy", "noeviction");
        }
    }

    @Test
    void consumesExactlyCapacityThenDeniesWithoutWritingOrRenewingTtl() throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            var executions = new ArrayList<BucketExecution>();
            var store = observedStore(commands, executions);
            var policy = policy(3, 1, 1000);
            String key = key();
            for (int i = 0; i < 3; i++) assertThat(store.consume(key, policy).outcome())
                    .isEqualTo(RateLimitDecision.Outcome.ALLOWED);
            int accepted = 3;
            for (int i = 0; i < 5; i++) {
                RateLimitDecision decision = store.consume(key, policy);
                BucketExecution execution = executions.getLast();
                JsonNode saved = JSON.readTree(execution.after().raw());
                if (decision.outcome() == RateLimitDecision.Outcome.ALLOWED) {
                    accepted++;
                    assertThat(saved.get("credit").longValue()).isBetween(0L, 2000L);
                } else {
                    assertThat(decision.outcome()).isEqualTo(RateLimitDecision.Outcome.REJECTED);
                    execution.assertUnchanged();
                    assertThat(saved.get("credit").longValue()).isBetween(0L, 999L);
                }
                assertThat(saved.get("schema").intValue()).isEqualTo(1);
                assertThat(saved.get("fingerprint").textValue()).isEqualTo(policy.fingerprint());
                assertThat(execution.after().ttl()).isBetween(0L, 3000L);
            }
            long elapsed = executions.getLast().after().afterMs() - executions.getFirst().before().beforeMs();
            long refillAllowance = elapsed * policy.refillTokens() / policy.periodMs();
            assertThat((long) accepted).isBetween(3L, policy.capacity() + refillAllowance);
            if (refillAllowance == 0) {
                assertThat(accepted).as("exact initial capacity before any token can refill").isEqualTo(3);
                assertThat(executions.getLast().after().raw()).isEqualTo(executions.get(2).after().raw());
            }
        }
    }

    @Test
    void continuouslyRefillsBeforeBucketExpiry() throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            var executions = new ArrayList<BucketExecution>();
            var store = observedStore(commands, executions);
            var policy = policy(10, 10, 1000);
            String key = key();
            for (int i = 0; i < 10; i++) store.consume(key, policy);
            for (int i = 0; i < 5; i++) {
                boolean[] exhausted = {false};
                await(() -> {
                    RateLimitDecision decision = store.consume(key, policy);
                    BucketExecution execution = executions.getLast();
                    // A pause may outlast the bucket. A fresh allowance is not evidence of refill:
                    // deplete that bucket and observe a denial before accepting the next allowance.
                    if (execution.before().raw().isEmpty()) exhausted[0] = false;
                    if (decision.outcome() == RateLimitDecision.Outcome.REJECTED) {
                        exhausted[0] = true;
                        return false;
                    }
                    return exhausted[0] && decision.outcome() == RateLimitDecision.Outcome.ALLOWED;
                });
                BucketExecution refill = executions.getLast();
                assertThat(refill.before().raw()).as("refill must consume an existing bucket").isNotEmpty();
                assertThat(refill.before().ttl()).as("allowance before full bucket expiry").isPositive();
                assertThat(JSON.readTree(refill.before().raw()).get("credit").longValue()).isBetween(0L, 999L);
                assertThat(refill.after().ttl()).isBetween(1L, 1000L);
                assertThat(JSON.readTree(refill.after().raw()).get("credit").longValue()).isBetween(0L, 9000L);
            }
        }
    }

    @Test
    void expiresOneSecondBucketAndReinitializesFull() throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            var store = store(commands);
            var policy = policy(1, 1, 1000);
            String key = key();
            assertThat(store.consume(key, policy)).isEqualTo(RateLimitDecision.allowed());
            await(() -> commands.exists(key) == 0);
            assertThat(store.consume(key, policy)).isEqualTo(RateLimitDecision.allowed());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrongtype", "json", "array", "null", "oversized", "missing-ttl", "schema",
            "missing-fingerprint", "different-fingerprint", "future", "negative-time", "fractional-time",
            "negative-credit", "fractional-credit", "excess-credit", "infinite-credit", "missing-credit"})
    void rejectsInvalidStateWithoutChangingIt(String defect) throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            var policy = policy(2, 1, 1000);
            String key = key();
            String valid = state(policy, 1000, now(commands));
            String raw = switch (defect) {
                case "json" -> "{";
                case "array" -> "[]";
                case "null" -> "null";
                case "oversized" -> " ".repeat(513) + valid;
                case "schema" -> valid.replace("\"schema\":1", "\"schema\":2");
                case "missing-fingerprint" -> valid.replace("\"fingerprint\":", "\"other\":");
                case "different-fingerprint" -> valid.replace(policy.fingerprint(), "a".repeat(64));
                case "future" -> state(policy, 1000, now(commands) + 61000);
                case "negative-time" -> state(policy, 1000, -1);
                case "fractional-time" -> valid.replace("\"updatedAtMs\":", "\"updatedAtMs\":0.5,\"unused\":");
                case "negative-credit" -> state(policy, -1, now(commands));
                case "fractional-credit" -> valid.replace("\"credit\":1000", "\"credit\":0.5");
                case "excess-credit" -> state(policy, 2001, now(commands));
                case "infinite-credit" -> valid.replace("\"credit\":1000", "\"credit\":1e999");
                case "missing-credit" -> valid.replace("\"credit\":", "\"unused\":");
                default -> valid;
            };
            if (defect.equals("wrongtype")) {
                commands.lpush(key, "unchanged");
                commands.pexpire(key, 10000);
            } else if (defect.equals("missing-ttl")) {
                commands.set(key, raw);
            } else {
                commands.psetex(key, 10000, raw);
            }
            long ttl = commands.pttl(key);
            assertThatThrownBy(() -> store(commands).consume(key, policy))
                    .isInstanceOfSatisfying(RateLimitException.class,
                            error -> assertThat(error.reason()).isEqualTo(RateLimitException.Reason.UNAVAILABLE));
            if (defect.equals("wrongtype")) assertThat(commands.lrange(key, 0, -1)).containsExactly("unchanged");
            else assertThat(commands.get(key)).isEqualTo(raw);
            if (ttl == -1) assertThat(commands.pttl(key)).isEqualTo(-1);
            else assertThat(commands.pttl(key)).isBetween(1L, ttl);
        }
    }

    @Test
    void preservesExactLargeIntegerCreditsAcrossJsonRoundTrips() throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            var policy = policy(1000000, 1000000, 86400000);
            String key = key();
            long future = now(commands) + 50000;
            long credit = policy.capacityCredit() - 1;
            commands.psetex(key, 86400000, state(policy, credit, future));
            for (int i = 1; i <= 3; i++) {
                assertThat(store(commands).consume(key, policy)).isEqualTo(RateLimitDecision.allowed());
                JsonNode saved = state(commands, key);
                assertThat(saved.get("credit").longValue()).isEqualTo(credit - i * policy.periodMs());
                assertThat(saved.get("updatedAtMs").longValue()).isEqualTo(future);
            }
            assertThat(commands.pttl(key)).isBetween(1L, 86460000L);
        }
    }

    @Test
    void clockRegressionKeepsFutureTimestampAndIncludesWaitInRetry() throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            var policy = policy(1, 10, 1000);
            var executions = new ArrayList<BucketExecution>();
            var store = observedStore(commands, executions);
            String key = key();
            long future = now(commands) + 500;
            commands.psetex(key, 5000, state(policy, 1000, future));
            assertThat(store.consume(key, policy)).isEqualTo(RateLimitDecision.allowed());
            BucketSnapshot allowed = executions.getLast().after();
            JsonNode saved = JSON.readTree(allowed.raw());
            long storedLast = saved.get("updatedAtMs").longValue();
            assertThat(storedLast).isEqualTo(future);
            long debt = policy.capacityCredit() - saved.get("credit").longValue();
            long refillMs = (debt + policy.refillTokens() - 1) / policy.refillTokens();
            long expectedExpiry = storedLast + refillMs;
            allowed.assertExpiresAt(expectedExpiry);
            RateLimitDecision denied = store.consume(key, policy);
            BucketExecution deniedExecution = executions.getLast();
            long before = deniedExecution.before().beforeMs();
            long after = deniedExecution.after().afterMs();
            assertThat(denied.outcome()).isEqualTo(RateLimitDecision.Outcome.REJECTED);
            assertThat(denied.retryDelayMs()).isBetween(Math.max(1, future - after + 100),
                    Math.max(1, future - before + 100));
            assertThat(deniedExecution.after().raw()).isEqualTo(allowed.raw());
            deniedExecution.assertUnchanged();
            deniedExecution.after().assertExpiresAt(expectedExpiry);
            await(() -> store(commands).consume(key, policy).outcome() == RateLimitDecision.Outcome.ALLOWED);
            assertThat(state(commands, key).get("updatedAtMs").longValue()).isGreaterThanOrEqualTo(future);
        }
    }

    @Test
    void independentClientsAtomicallyShareCapacity() throws Exception {
        var secondClient = newRedisClient();
        try (var first = redisClient.connect(); var second = secondClient.connect();
                var workers = Executors.newFixedThreadPool(2)) {
            var policy = policy(10, 10, 1000);
            String key = key();
            var barrier = new CyclicBarrier(2);
            long start = now(first.sync());
            var results = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (var connection : List.of(first, second)) {
                results.add(workers.submit(() -> {
                    var store = store(connection.sync());
                    barrier.await(5, TimeUnit.SECONDS);
                    int accepted = 0;
                    for (int i = 0; i < 100; i++) {
                        if (store.consume(key, policy).outcome() == RateLimitDecision.Outcome.ALLOWED) accepted++;
                    }
                    return accepted;
                }));
            }
            int accepted = results.get(0).get(10, TimeUnit.SECONDS) + results.get(1).get(10, TimeUnit.SECONDS);
            long elapsed = now(first.sync()) - start;
            assertThat(accepted).isBetween(10, (int) (10 + elapsed * 10 / 1000));
            assertThat(state(first.sync(), key).get("credit").longValue()).isBetween(0L, 10000L);
            assertThat(first.sync().pttl(key)).isBetween(1L, 1000L);
        } finally {
            secondClient.shutdown();
        }
    }

    @Test
    void revisionAndNamespaceAreIsolatedWhileSameKeyChangedConfigFailsClosed() throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            var executions = new ArrayList<BucketExecution>();
            var store = observedStore(commands, executions);
            var policy = policy(1, 1, 10000);
            String policyId = "test-" + UUID.randomUUID();
            var factory = new RateLimitKeyFactory(new RateLimitProperties(
                    true, "task4-a", "test", null, null, null, null));
            var otherNamespace = new RateLimitKeyFactory(new RateLimitProperties(
                    true, "task4-b", "test", null, null, null, null));
            String original = factory.key(policyId, policy, policy.dimension(), "192.0.2.1");
            assertThat(store.consume(original, policy)).isEqualTo(RateLimitDecision.allowed());
            BucketSnapshot retained = executions.getLast().after();
            JsonNode originalState = JSON.readTree(retained.raw());
            long debt = policy.capacityCredit() - originalState.get("credit").longValue();
            long expiry = originalState.get("updatedAtMs").longValue()
                    + (debt + policy.refillTokens() - 1) / policy.refillTokens();
            retained.assertExpiresAt(expiry);
            assertThat(store.consume(otherNamespace.key(policyId, policy, policy.dimension(), "192.0.2.1"), policy))
                    .isEqualTo(RateLimitDecision.allowed());
            var revised = new RateLimitPolicy(policy.dimension(), "r2", 1, 1, Duration.ofSeconds(10));
            assertThat(store.consume(factory.key(policyId, revised, revised.dimension(), "192.0.2.1"), revised))
                    .isEqualTo(RateLimitDecision.allowed());
            // Ensure real Redis time passes: refreshing the original TTL must move its expiry.
            await(() -> now(commands) >= retained.afterMs() + 25);
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> store.consume(original, policy(2, 1, 10000)))
                        .isInstanceOfSatisfying(RateLimitException.class,
                                error -> assertThat(error.reason()).isEqualTo(RateLimitException.Reason.UNAVAILABLE));
                BucketExecution failed = executions.getLast();
                assertThat(failed.before().raw()).isEqualTo(retained.raw());
                failed.assertUnchanged();
                failed.before().assertExpiresAt(expiry);
                failed.after().assertExpiresAt(expiry);
            }
        }
    }

    @Test
    void rollbackToExactOriginalRevisionAndSecretResumesRetainedBudget() throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            var executions = new ArrayList<BucketExecution>();
            var store = observedStore(commands, executions);
            var originalPolicy = policy(3, 1, 10000);
            var newPolicy = new RateLimitPolicy(originalPolicy.dimension(), "r2", 3, 1, Duration.ofSeconds(10));
            String secretA = "task4-lifecycle-secret-A-32-bytes-minimum";
            String secretB = "task4-lifecycle-secret-B-32-bytes-minimum";
            var factoryA = lifecycleFactory(secretA);
            var factoryB = lifecycleFactory(secretB);
            String policyId = "rollback-" + UUID.randomUUID();
            String originalKey = lifecycleKey(factoryA, policyId, originalPolicy);
            String newKey = lifecycleKey(factoryB, policyId, newPolicy);
            assertThat(List.of(originalKey, newKey, lifecycleKey(factoryA, policyId, newPolicy),
                    lifecycleKey(factoryB, policyId, originalPolicy))).doesNotHaveDuplicates();

            for (int i = 0; i < 2; i++) assertThat(store.consume(originalKey, originalPolicy))
                    .isEqualTo(RateLimitDecision.allowed());
            BucketSnapshot retained = executions.getLast().after();
            JsonNode retainedState = JSON.readTree(retained.raw());
            assertRetainedDebit(originalPolicy, executions.getLast());
            assertThat(retainedState.get("credit").longValue()).isBetween(10000L, 20000L);
            for (int i = 0; i < 3; i++) assertThat(store.consume(newKey, newPolicy))
                    .isEqualTo(RateLimitDecision.allowed());
            BucketSnapshot newBudget = executions.getLast().after();
            assertThat(newBudget.raw()).isNotEqualTo(retained.raw());
            assertThat(snapshot(commands, originalKey).raw()).isEqualTo(retained.raw());

            // Recreate the exact old configuration, including its original HMAC secret.
            String restoredKey = lifecycleKey(lifecycleFactory(secretA), policyId,
                    new RateLimitPolicy(originalPolicy.dimension(), "r1", 3, 1, Duration.ofSeconds(10)));
            assertThat(restoredKey).isEqualTo(originalKey);
            int accepted = 0;
            for (int i = 0; i < 4; i++) {
                RateLimitDecision decision = store.consume(restoredKey, originalPolicy);
                BucketExecution restored = executions.getLast();
                if (i == 0) {
                    assertThat(restored.before().raw()).isEqualTo(retained.raw());
                    assertThat(decision).as("one retained token survives new-key exhaustion")
                            .isEqualTo(RateLimitDecision.allowed());
                }
                if (decision.outcome() == RateLimitDecision.Outcome.ALLOWED) {
                    accepted++;
                    assertRetainedDebit(originalPolicy, restored);
                } else {
                    assertThat(decision.outcome()).isEqualTo(RateLimitDecision.Outcome.REJECTED);
                    restored.assertUnchanged();
                }
            }
            long elapsed = executions.getLast().after().afterMs() - retainedState.get("updatedAtMs").longValue();
            long budget = (retainedState.get("credit").longValue() + elapsed * originalPolicy.refillTokens())
                    / originalPolicy.periodMs();
            assertThat((long) accepted).isBetween(1L, budget);
            if (budget == 1) assertThat(accepted).isEqualTo(1);
            assertThat(snapshot(commands, newKey).raw()).as("rollback must not merge or spend new-key usage")
                    .isEqualTo(newBudget.raw());
        }
    }

    @Test
    void rollbackAfterNaturalFullRefillExpiryStartsWithFullOriginalBudget() throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            var executions = new ArrayList<BucketExecution>();
            var store = observedStore(commands, executions);
            var originalPolicy = policy(3, 1, 1000);
            var newPolicy = new RateLimitPolicy(originalPolicy.dimension(), "r2", 3, 1, Duration.ofSeconds(10));
            String secretA = "task4-expiry-secret-A-32-bytes-minimum";
            String secretB = "task4-expiry-secret-B-32-bytes-minimum";
            String policyId = "rollback-" + UUID.randomUUID();
            String originalKey = lifecycleKey(lifecycleFactory(secretA), policyId, originalPolicy);
            String newKey = lifecycleKey(lifecycleFactory(secretB), policyId, newPolicy);
            assertThat(newKey).isNotEqualTo(originalKey);
            for (int i = 0; i < 2; i++) assertThat(store.consume(originalKey, originalPolicy))
                    .isEqualTo(RateLimitDecision.allowed());
            BucketSnapshot retained = executions.getLast().after();
            JsonNode old = JSON.readTree(retained.raw());
            long debt = originalPolicy.capacityCredit() - old.get("credit").longValue();
            long fullRefillExpiry = old.get("updatedAtMs").longValue()
                    + (debt + originalPolicy.refillTokens() - 1) / originalPolicy.refillTokens();
            retained.assertExpiresAt(fullRefillExpiry);
            for (int i = 0; i < 2; i++) assertThat(store.consume(newKey, newPolicy))
                    .isEqualTo(RateLimitDecision.allowed());
            BucketSnapshot newBudget = executions.getLast().after();

            // Observe Redis's actual natural expiry; never shorten the TTL or delete the key.
            await(() -> commands.exists(originalKey) == 0);
            assertThat(now(commands)).isGreaterThanOrEqualTo(fullRefillExpiry);
            String restoredKey = lifecycleKey(lifecycleFactory(secretA), policyId, originalPolicy);
            assertThat(restoredKey).isEqualTo(originalKey);
            assertThat(store.consume(restoredKey, originalPolicy)).isEqualTo(RateLimitDecision.allowed());
            BucketExecution restored = executions.getLast();
            assertThat(restored.before().raw()).isEmpty();
            assertThat(restored.before().ttl()).isEqualTo(-2);
            JsonNode fresh = JSON.readTree(restored.after().raw());
            assertThat(fresh.get("credit").longValue())
                    .isEqualTo(originalPolicy.capacityCredit() - originalPolicy.periodMs());
            assertThat(fresh.get("updatedAtMs").longValue())
                    .isBetween(restored.before().beforeMs(), restored.after().afterMs());
            assertThat(fresh.get("fingerprint").textValue()).isEqualTo(originalPolicy.fingerprint());
            assertThat(snapshot(commands, newKey).raw()).isEqualTo(newBudget.raw());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"capacity-zero", "capacity-large", "capacity-fraction", "refill-zero",
            "refill-large", "period-small", "period-large", "period-fraction", "refill-horizon",
            "fingerprint-short", "fingerprint-uppercase", "fingerprint-nonhex", "missing-arg", "extra-arg", "two-keys"})
    void validatesLuaArgumentsBeforeMutation(String defect) {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            String key = key();
            var store = new RedisTokenBucketStore((sha, source, bucket, args) -> {
                var invalid = new ArrayList<>(args);
                switch (defect) {
                    case "capacity-zero" -> invalid.set(0, "0");
                    case "capacity-large" -> invalid.set(0, "1000001");
                    case "capacity-fraction" -> invalid.set(0, "1.5");
                    case "refill-zero" -> invalid.set(1, "0");
                    case "refill-large" -> invalid.set(1, "1000001");
                    case "period-small" -> invalid.set(2, "999");
                    case "period-large" -> invalid.set(2, "86400001");
                    case "period-fraction" -> invalid.set(2, "1000.5");
                    case "refill-horizon" -> invalid.set(0, "1000000");
                    case "fingerprint-short" -> invalid.set(3, "a");
                    case "fingerprint-uppercase" -> invalid.set(3, "A".repeat(64));
                    case "fingerprint-nonhex" -> invalid.set(3, "g".repeat(64));
                    case "missing-arg" -> invalid.removeLast();
                    case "extra-arg" -> invalid.add("extra");
                    default -> { }
                }
                String[] keys = defect.equals("two-keys") ? new String[] {bucket, bucket + ":other"}
                        : new String[] {bucket};
                return commands.eval(source, ScriptOutputType.MULTI, keys, invalid.toArray(String[]::new));
            });
            assertThatThrownBy(() -> store.consume(key, policy(1, 1, 1000))).isInstanceOf(RateLimitException.class);
            assertThat(commands.exists(key, key + ":other")).isZero();
        }
    }

    @Test
    void clampsOldStateToFullRefillAndAcceptsZeroIntegerBoundaries() throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            var policy = policy(2, 1, 1000);
            String key = key();
            commands.psetex(key, 5000, state(policy, 0, 0));
            assertThat(store(commands).consume(key, policy)).isEqualTo(RateLimitDecision.allowed());
            assertThat(state(commands, key).get("credit").longValue()).isEqualTo(1000);
            assertThat(commands.pttl(key)).isBetween(1L, 1000L);
        }
    }
}
