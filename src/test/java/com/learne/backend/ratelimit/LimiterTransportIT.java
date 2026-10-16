package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LimiterTransportIT extends RedisTestSupport {
    private static final RateLimitPolicy POLICY = new RateLimitPolicy(RateLimitDimension.IP, "r1", 1000, 1, Duration.ofMillis(86400));
    private static String key() { return "transport:" + UUID.randomUUID(); }
    private static void allowed(RedisTokenBucketStore store, String key) { assertThat(store.consume(key, POLICY).outcome()).isEqualTo(RateLimitDecision.Outcome.ALLOWED); }
    private static void unavailable(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RateLimitException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(RateLimitException.Reason.UNAVAILABLE);
            assertThat(failure).hasNoCause().hasMessage("Rate limiting unavailable");
        });
    }
    private static long credit(RedisCommands<String, String> observer, String key) throws Exception {
        return new ObjectMapper().readTree(observer.get(key)).get("credit").longValue() / POLICY.periodMs();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition within five seconds").isLessThan(end);
            Thread.sleep(5);
        }
    }
    private static void assertDeadline(long start) {
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThanOrEqualTo(2500);
    }

    @Test
    void scriptFlushProducesExactlyOneEvalFallbackAndOneConsume() throws Exception {
        try (var observer = redisClient.connect(); var proxy = new RespFaultProxy(redisUri());
                var transport = new LimiterRedisConnection(proxy.uri())) {
            var store = new RedisTokenBucketStore(transport);
            allowed(store, key());
            observer.sync().scriptFlush();
            int sha = proxy.forwarded("EVALSHA"), eval = proxy.forwarded("EVAL");
            String key = key();
            allowed(store, key);
            assertThat(proxy.forwarded("EVALSHA") - sha).isEqualTo(1);
            assertThat(proxy.forwarded("EVAL") - eval).isEqualTo(1);
            assertThat(credit(observer.sync(), key)).isEqualTo(999);
            assertThat(proxy.connections()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(value = RespFaultProxy.Action.class, names = {"DROP_BEFORE", "DISCONNECT_BEFORE"})
    void requestNotForwardedNeverDispatchesOnRecovery(RespFaultProxy.Action fault) throws Exception {
        try (var observer = redisClient.connect(); var proxy = new RespFaultProxy(redisUri());
                var transport = new LimiterRedisConnection(proxy.uri())) {
            var store = new RedisTokenBucketStore(transport);
            allowed(store, key());
            int sha = proxy.forwarded("EVALSHA"), eval = proxy.forwarded("EVAL");
            proxy.fault("EVALSHA", fault);
            String lost = key();
            long start = System.nanoTime();
            unavailable(() -> store.consume(lost, POLICY));
            assertDeadline(start);
            assertThat(observer.sync().get(lost)).isNull();
            proxy.recover();
            Thread.sleep(150);
            assertThat(observer.sync().get(lost)).isNull();
            assertThat(proxy.forwarded("EVALSHA")).isEqualTo(sha);
            assertThat(proxy.forwarded("EVAL")).isEqualTo(eval);
            allowed(store, key());
            assertThat(proxy.connections()).isEqualTo(2);
            assertThat(observer.sync().get(lost)).isNull();
            assertThat(proxy.forwarded("EVALSHA")).isEqualTo(sha + 1);
        }
    }

    @ParameterizedTest
    @EnumSource(value = RespFaultProxy.Action.class, names = {"DROP_REPLY", "DISCONNECT_REPLY"})
    void executedCommandWithLostResponseIsNeverReplayed(RespFaultProxy.Action fault) throws Exception {
        try (var observer = redisClient.connect(); var proxy = new RespFaultProxy(redisUri());
                var transport = new LimiterRedisConnection(proxy.uri())) {
            var store = new RedisTokenBucketStore(transport);
            allowed(store, key());
            int sha = proxy.forwarded("EVALSHA"), eval = proxy.forwarded("EVAL");
            String lost = key();
            proxy.fault("EVALSHA", fault);
            long start = System.nanoTime();
            unavailable(() -> store.consume(lost, POLICY));
            assertDeadline(start);
            await(() -> observer.sync().exists(lost) == 1);
            assertThat(credit(observer.sync(), lost)).isEqualTo(999);
            String state = observer.sync().get(lost);
            proxy.recover();
            Thread.sleep(150);
            assertThat(observer.sync().get(lost)).isEqualTo(state);
            assertThat(proxy.forwarded("EVALSHA")).isEqualTo(sha + 1);
            assertThat(proxy.forwarded("EVAL")).isEqualTo(eval);
            allowed(store, lost);
            assertThat(credit(observer.sync(), lost)).isEqualTo(998);
            assertThat(proxy.connections()).isEqualTo(2);
            assertThat(proxy.forwarded("EVALSHA")).isEqualTo(sha + 2);
        }
    }

    @Test
    void totalConnectNoScriptAndFallbackDelayShareOneDeadline() throws Exception {
        try (var observer = redisClient.connect(); var proxy = new RespFaultProxy(redisUri());
                var transport = new LimiterRedisConnection(proxy.uri())) {
            observer.sync().scriptFlush();
            proxy.delay("HELLO", 650);
            proxy.delay("EVALSHA", 650);
            proxy.delay("EVAL", 1100);
            String lost = key();
            var store = new RedisTokenBucketStore(transport);
            long start = System.nanoTime();
            unavailable(() -> store.consume(lost, POLICY));
            assertDeadline(start);
            assertThat(proxy.forwarded("HELLO")).isEqualTo(1);
            assertThat(proxy.forwarded("EVALSHA")).isEqualTo(1);
            assertThat(proxy.forwarded("EVAL")).isEqualTo(1);
            assertThat(credit(observer.sync(), lost)).isEqualTo(999);
            proxy.recover();
            Thread.sleep(25); // Allow the asynchronous close acknowledgement, without awaiting it in production.
            allowed(store, key());
            Thread.sleep(600); // Old delayed reply must not retire the replacement generation.
            allowed(store, key());
            assertThat(proxy.connections()).isEqualTo(2);
            assertThat(proxy.forwarded("EVALSHA")).isEqualTo(3);
            assertThat(proxy.forwarded("EVAL")).isEqualTo(1);
        }
    }

    @Test
    void handshakeTimeoutCannotPublishOrLateDispatchAfterRecovery() throws Exception {
        try (var observer = redisClient.connect(); var proxy = new RespFaultProxy(redisUri());
                var transport = new LimiterRedisConnection(proxy.uri())) {
            proxy.delay("HELLO", 2300);
            var store = new RedisTokenBucketStore(transport);
            String lost = key();
            long start = System.nanoTime();
            unavailable(() -> store.consume(lost, POLICY));
            assertDeadline(start);
            proxy.recover();
            Thread.sleep(1100); // Includes failed-connect cooldown and the late HELLO response.
            assertThat(observer.sync().get(lost)).isNull();
            assertThat(proxy.received("EVALSHA")).isZero();
            assertThat(proxy.received("EVAL")).isZero();
            allowed(store, key());
            assertThat(proxy.connections()).isEqualTo(2);
            assertThat(observer.sync().get(lost)).isNull();
        }
    }

    @Test
    void sixtyFourRealInflightTimeoutsReleaseAllPermitsWithoutReplay() throws Exception {
        try (var observer = redisClient.connect(); var proxy = new RespFaultProxy(redisUri());
                var transport = new LimiterRedisConnection(proxy.uri());
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var store = new RedisTokenBucketStore(transport);
            allowed(store, key());
            for (int round = 0; round < 2; round++) {
                int sha = proxy.forwarded("EVALSHA");
                proxy.fault("EVALSHA", RespFaultProxy.Action.DROP_REPLY);
                var lost = new ArrayList<String>();
                var calls = new ArrayList<Future<?>>();
                long start = System.nanoTime();
                for (int i = 0; i < 64; i++) {
                    String key = key();
                    lost.add(key);
                    calls.add(pool.submit(() -> unavailable(() -> store.consume(key, POLICY))));
                }
                await(() -> proxy.replies("EVALSHA") == sha + 64);
                long saturatedStart = System.nanoTime();
                String excess = key();
                unavailable(() -> store.consume(excess, POLICY));
                assertThat(System.nanoTime() - saturatedStart).isLessThan(100_000_000L);
                for (var call : calls) call.get(3, TimeUnit.SECONDS);
                assertDeadline(start);
                for (String key : lost) assertThat(credit(observer.sync(), key)).isEqualTo(999);
                assertThat(observer.sync().get(excess)).isNull();
                proxy.recover();
                Thread.sleep(150);
                assertThat(proxy.forwarded("EVALSHA")).isEqualTo(sha + 64);
                allowed(store, key());
                assertThat(proxy.connections()).isEqualTo(round + 2);
                assertThat(proxy.forwarded("EVALSHA")).isEqualTo(sha + 65);
                for (String key : lost) assertThat(credit(observer.sync(), key)).isEqualTo(999);
            }
        }
    }

    @Test
    void closingLimiterDoesNotCloseObserverCacheClientOrItsEventLoops() throws Exception {
        try (var observer = redisClient.connect()) {
            var transport = new LimiterRedisConnection(redisUri());
            allowed(new RedisTokenBucketStore(transport), key());
            transport.close();
            transport.close();
            var connector = org.springframework.test.util.ReflectionTestUtils.getField(transport, "connector");
            var resources = (io.lettuce.core.resource.DefaultClientResources)
                    org.springframework.test.util.ReflectionTestUtils.getField(connector, "resources");
            await(() -> resources.eventExecutorGroup().isTerminated());
            assertThat(observer.sync().ping()).isEqualTo("PONG");
            try (var another = redisClient.connect()) { assertThat(another.sync().ping()).isEqualTo("PONG"); }
        }
    }

    @Test
    void shutdownDuringRealHandshakeClosesChannelAndCannotLateDispatch() throws Exception {
        try (var observer = redisClient.connect(); var proxy = new RespFaultProxy(redisUri());
                var transport = new LimiterRedisConnection(proxy.uri());
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            long clients = observer.sync().clientList().lines().count();
            proxy.delay("HELLO", 900);
            String lost = key();
            var store = new RedisTokenBucketStore(transport);
            var call = pool.submit(() -> unavailable(() -> store.consume(lost, POLICY)));
            await(() -> proxy.forwarded("HELLO") == 1);
            long start = System.nanoTime();
            transport.close();
            call.get(500, TimeUnit.MILLISECONDS);
            assertThat(System.nanoTime() - start).isLessThan(500_000_000L);
            proxy.recover();
            Thread.sleep(1000);
            assertThat(observer.sync().get(lost)).isNull();
            assertThat(proxy.received("EVALSHA")).isZero();
            assertThat(proxy.received("EVAL")).isZero();
            await(() -> observer.sync().clientList().lines().count() == clients);
            assertThat(observer.sync().ping()).isEqualTo("PONG");
        }
    }

    @Test
    void aclHandshakeAndDatabaseSelectionPassThroughProxyNormally() throws Exception {
        String user = "transport-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        try (var observer = redisClient.connect(); var proxy = new RespFaultProxy(redisUri())) {
            observer.sync().aclSetuser(user, new io.lettuce.core.AclSetuserArgs()
                    .on().addPassword(password).allCommands().allKeys());
            try {
                var uri = io.lettuce.core.RedisURI.builder(proxy.uri()).withAuthentication(user, password).withDatabase(3).build();
                try (var transport = new LimiterRedisConnection(uri)) {
                    String key = key();
                    allowed(new RedisTokenBucketStore(transport), key);
                    assertThat(observer.sync().get(key)).isNull();
                    observer.sync().select(3);
                    assertThat(credit(observer.sync(), key)).isEqualTo(999);
                    assertThat(proxy.forwarded("HELLO")).isEqualTo(1);
                    assertThat(proxy.forwarded("SELECT")).isEqualTo(1);
                    assertThat(proxy.forwarded("EVALSHA")).isEqualTo(1);
                }
            } finally { observer.sync().aclDeluser(user); }
        }
    }

    @Test
    void reportsResolvedDriverStackAndServerVersion() throws Exception {
        assertThat(RedisClient.class.getPackage().getImplementationVersion()).isEqualTo("7.5.2.RELEASE/5728917");
        assertThat(org.springframework.data.redis.core.RedisTemplate.class.getPackage().getImplementationVersion()).isEqualTo("4.1.0");
        assertThat(io.netty.util.Version.identify().get("netty-common").artifactVersion()).isEqualTo("4.2.15.Final");
        try (var observer = redisClient.connect()) {
            assertThat(observer.sync().info("server")).contains("redis_version:7.4.11");
        }
    }
}
