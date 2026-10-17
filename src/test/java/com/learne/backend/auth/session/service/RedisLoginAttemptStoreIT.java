package com.learne.backend.auth.session.service;

import static org.assertj.core.api.Assertions.*;

import com.learne.backend.ratelimit.*;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class RedisLoginAttemptStoreIT extends RedisTestSupport {
    private static final RateLimitProperties PROPS = new RateLimitProperties(true, "login-it", "test", null, null, null, null);
    private static final RateLimitKeyFactory KEYS = new RateLimitKeyFactory(PROPS);
    private static final String SNAPSHOT = """
            local function snapshot()
                local before = redis.call('TIME')
                local ttl = redis.call('PTTL', KEYS[1])
                local after = redis.call('TIME')
                return {before, after, ttl}
            end
            """;

    private static String email() { return UUID.randomUUID() + "@example.com"; }
    private static String key(String email) {
        return "login-it:test:auth:login-failures:v1:email:"
                + KEYS.hmacCanonicalIdentifier(RateLimitDimension.EMAIL, email);
    }
    private static RedisLoginAttemptStore store(RedisTokenBucketStore.ScriptExecutor executor) {
        return new RedisLoginAttemptStore(PROPS, KEYS, executor);
    }
    private static String source() throws Exception {
        try (var stream = RedisLoginAttemptStore.class.getResourceAsStream("/auth/login-attempts.lua")) {
            assertThat(stream).as("production Lua resource").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static List<?> raw(RedisCommands<String, String> commands, String source, String key, String... args) {
        return commands.eval(source, ScriptOutputType.MULTI, new String[] {key}, args);
    }
    private static long millis(List<?> time) {
        return Long.parseLong(time.get(0).toString()) * 1000 + Long.parseLong(time.get(1).toString()) / 1000;
    }
    private record Expiry(long low, long high, long ttl) {
        static Expiry decode(List<?> value) {
            long ttl = (Long) value.get(2);
            return new Expiry(millis((List<?>) value.get(0)) + ttl, millis((List<?>) value.get(1)) + ttl, ttl);
        }
        void unchangedFrom(Expiry before) {
            assertThat(ttl).isBetween(0L, before.ttl);
            assertThat(low).isLessThanOrEqualTo(before.high);
            assertThat(high).isGreaterThanOrEqualTo(before.low);
        }
    }
    private static Expiry expiry(RedisCommands<String, String> commands, String key) {
        return Expiry.decode(raw(commands, SNAPSHOT + "return snapshot()", key));
    }
    private static void unavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RateLimitException.class, error -> {
            assertThat(error.reason()).isEqualTo(RateLimitException.Reason.UNAVAILABLE);
            assertThat(error.getCause()).isNull();
        });
    }
    private static void blocked(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RateLimitException.class, error -> {
            assertThat(error.reason()).isEqualTo(RateLimitException.Reason.REJECTED);
            assertThat(error.retryDelayMs()).isBetween(1L, 900000L);
        });
    }

    @Test
    void productionTransportRecordsFiveThenLocksWithoutRenewingAndClearsValidOrAbsentState() {
        try (var raw = redisClient.connect(); var transport = new LimiterRedisConnection(redisUri())) {
            var commands = raw.sync();
            assertThat(commands.info("server")).contains("redis_version:7.4.11");
            commands.scriptFlush(); // Exercise Phase 1 NOSCRIPT recovery using the production store.
            var store = store(transport);
            String email = email();
            String key = key(email);
            store.assertCanAttempt(email);
            store.clear(email);
            assertThat(commands.exists(key)).isZero();
            for (int i = 1; i <= 5; i++) {
                store.assertCanAttempt(email);
                store.recordFailure(email);
                assertThat(commands.get(key)).isEqualTo(Integer.toString(i));
                assertThat(commands.pttl(key)).isBetween(1L, 900000L);
            }
            Expiry locked = expiry(commands, key);
            blocked(() -> store.assertCanAttempt(email));
            blocked(() -> store.recordFailure(email));
            assertThat(commands.get(key)).isEqualTo("5");
            expiry(commands, key).unchangedFrom(locked);
            store.clear(email);
            store.assertCanAttempt(email);
            assertThat(commands.exists(key)).isZero();
            store.recordFailure(email);
            assertThat(commands.get(key)).isEqualTo("1");
        }
    }

    @Test
    void failureResetsExpiryBelowThresholdAndCheckAndLockedOperationsKeepAbsoluteExpiry() throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            String source = source();
            String key = key(email());
            // Bracket the unmodified production script atomically with Redis TIME/PTTL.
            String observed = SNAPSHOT + "local before = snapshot()\nlocal result = (function()\n"
                    + source + "\nend)()\nreturn {before, snapshot(), result}";
            commands.psetex(key, 10000, "1");
            List<?> result = raw(commands, observed, key, "failure", "5", "900000");
            Expiry before = Expiry.decode((List<?>) result.get(0));
            Expiry after = Expiry.decode((List<?>) result.get(1));
            assertThat(after.low).isGreaterThan(before.high + 800000);
            assertThat(after.ttl).isEqualTo(900000);
            assertThat(commands.get(key)).isEqualTo("2");
            result = raw(commands, observed, key, "check", "5", "900000");
            Expiry.decode((List<?>) result.get(1)).unchangedFrom(Expiry.decode((List<?>) result.get(0)));
            commands.psetex(key, 10000, "5");
            for (String op : List.of("check", "failure")) {
                result = raw(commands, observed, key, op, "5", "900000");
                Expiry.decode((List<?>) result.get(1)).unchangedFrom(Expiry.decode((List<?>) result.get(0)));
                assertThat((List<?>) result.get(2)).hasSize(2).element(0).isEqualTo(0L);
                assertThat(commands.get(key)).isEqualTo("5");
            }
        }
    }

    @Test
    void shortRawFixtureNaturallyExpiresCooldownAndStartsAgain() throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            String source = source();
            String key = key(email());
            // One atomic fixture prevents a JVM pause from expiring the counter between increments.
            String fixture = "local function run()\n" + source + "\nend\n"
                    + "for i=1,5 do local r=run(); if r[1]~=1 then error('fixture') end end\nreturn run()";
            assertThat(raw(commands, fixture, key, "failure", "5", "120")).isEqualTo(List.of(0L, 120L));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (commands.exists(key) != 0) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(10);
            }
            assertThat(raw(commands, source, key, "check", "5", "120")).isEqualTo(List.of(1L, 0L));
            // Inspect within the same Redis execution rather than racing a 120ms JVM pause.
            assertThat(raw(commands, "local r=(function()\n" + source
                    + "\nend)(); return {r,redis.call('GET',KEYS[1]),redis.call('PTTL',KEYS[1])}",
                    key, "failure", "5", "120"))
                    .isEqualTo(List.of(List.of(1L, 0L), "1", 120L));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "6", "-1", "01", "1.0", "1e0", " 1", "1\n", "wrongtype", "no-ttl"})
    void corruptionFailsClosedForAllOperationsWithoutRepair(String raw) {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            var store = store((sha, source, key, args) -> raw(commands, source, key, args.toArray(String[]::new)));
            String email = email();
            String key = key(email);
            if (raw.equals("wrongtype")) { commands.lpush(key, "unchanged"); commands.pexpire(key, 60000); }
            else if (raw.equals("no-ttl")) commands.set(key, "3");
            else commands.psetex(key, 60000, raw);
            Expiry before = expiry(commands, key);
            unavailable(() -> store.assertCanAttempt(email));
            unavailable(() -> store.recordFailure(email));
            unavailable(() -> store.clear(email));
            if (raw.equals("wrongtype")) assertThat(commands.lrange(key, 0, -1)).containsExactly("unchanged");
            else assertThat(commands.get(key)).isEqualTo(raw.equals("no-ttl") ? "3" : raw);
            if (raw.equals("no-ttl")) assertThat(commands.pttl(key)).isEqualTo(-1);
            else expiry(commands, key).unchangedFrom(before);
        }
    }

    @Test
    void zeroPttlNormalizesToOneAndReachesRealHttpWriter() throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            String email = email();
            String key = key(email);
            commands.psetex(key, 60000, "5");
            Expiry before = expiry(commands, key);
            String override = """
                    local original = redis.call
                    local redis = {call = function(command, ...)
                        if command == 'PTTL' then return 0 end
                        return original(command, ...)
                    end}
                    """;
            var store = store((sha, source, bucket, args) -> {
                List<?> result = raw(commands, override + source, bucket, args.toArray(String[]::new));
                assertThat(result).isEqualTo(List.of(0L, 1L));
                return result;
            });
            for (Runnable action : List.<Runnable>of(() -> store.assertCanAttempt(email), () -> store.recordFailure(email))) {
                var response = new MockHttpServletResponse();
                try { action.run(); fail("expected rejection"); }
                catch (RateLimitException error) { new RateLimitErrorResponseFactory(new ObjectMapper()).write(response, error); }
                assertThat(response.getStatus()).isEqualTo(429);
                assertThat(response.getHeader("Retry-After")).isEqualTo("1");
            }
            expiry(commands, key).unchangedFrom(before);
        }
    }

    @Test
    void twoStoresShareSingleProductionTransportAndConcurrentFailuresCapAtFiveWithoutRenewal() throws Exception {
        try (var connection = redisClient.connect(); var transport = new LimiterRedisConnection(redisUri());
                var workers = Executors.newFixedThreadPool(2)) {
            var commands = connection.sync();
            var first = store(transport);
            var second = store(transport);
            String email = email();
            String key = key(email);
            first.assertCanAttempt(email); // Initialize the shared singleton transport before racing.
            var barrier = new CyclicBarrier(2);
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (var store : List.of(first, second)) futures.add(workers.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                int recorded = 0;
                for (int i = 0; i < 20; i++) {
                    try { store.recordFailure(email); recorded++; }
                    catch (RateLimitException error) { assertThat(error.reason()).isEqualTo(RateLimitException.Reason.REJECTED); }
                }
                return recorded;
            }));
            assertThat(futures.get(0).get(10, TimeUnit.SECONDS) + futures.get(1).get(10, TimeUnit.SECONDS)).isEqualTo(5);
            assertThat(commands.get(key)).isEqualTo("5");
            Expiry locked = expiry(commands, key);
            for (int i = 0; i < 10; i++) {
                blocked(() -> first.recordFailure(email));
                blocked(() -> second.assertCanAttempt(email));
            }
            expiry(commands, key).unchangedFrom(locked);
            second.clear(email);
            first.assertCanAttempt(email);
            first.recordFailure(email);
            assertThat(commands.get(key)).isEqualTo("1");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"op", "max", "ttl-zero", "ttl-large", "ttl-fraction", "ttl-text", "missing", "extra", "two-keys"})
    void invalidArgumentsNeverMutateExistingState(String defect) throws Exception {
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            String key = key(email());
            commands.psetex(key, 60000, "2");
            Expiry before = expiry(commands, key);
            var args = new ArrayList<>(List.of("failure", "5", "900000"));
            switch (defect) {
                case "op" -> args.set(0, "unknown");
                case "max" -> args.set(1, "6");
                case "ttl-zero" -> args.set(2, "0");
                case "ttl-large" -> args.set(2, "900001");
                case "ttl-fraction" -> args.set(2, "1.5");
                case "ttl-text" -> args.set(2, "oops");
                case "missing" -> args.removeLast();
                case "extra" -> args.add("extra");
                default -> { }
            }
            String source = source();
            String[] keys = defect.equals("two-keys") ? new String[] {key, key + ":other"} : new String[] {key};
            assertThatThrownBy(() -> commands.eval(source, ScriptOutputType.MULTI, keys, args.toArray(String[]::new)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(commands.get(key)).isEqualTo("2");
            expiry(commands, key).unchangedFrom(before);
            assertThat(commands.exists(key + ":other")).isZero();
        }
    }
}
