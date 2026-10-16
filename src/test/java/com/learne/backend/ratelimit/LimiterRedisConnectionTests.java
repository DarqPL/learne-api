package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;

class LimiterRedisConnectionTests {
    private static final List<String> ARGS = List.of("10", "1", "86400000", "fp");
    private static final List<Object> OK = List.of(1L, 0L);
    private final AtomicLong ticker = new AtomicLong();

    private static final class Attempt implements LimiterRedisConnection.ConnectAttempt {
        final CompletableFuture<StatefulRedisConnection<String, String>> result;
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        int aborts;
        Attempt() { this(new CompletableFuture<>()); }
        Attempt(CompletableFuture<StatefulRedisConnection<String, String>> result) { this.result = result; }
        public CompletableFuture<StatefulRedisConnection<String, String>> future() { return result; }
        public CompletableFuture<Void> abort() { aborts++; return closed; }
    }

    private static final class Connector implements LimiterRedisConnection.Connector {
        final Queue<Attempt> attempts = new ConcurrentLinkedQueue<>();
        final AtomicInteger calls = new AtomicInteger();
        int closes;
        public LimiterRedisConnection.ConnectAttempt connect() { calls.incrementAndGet(); return attempts.remove(); }
        public void close() { closes++; }
    }

    @SuppressWarnings("unchecked")
    private static StatefulRedisConnection<String, String> connection(RedisFuture<List<Object>> command) {
        var connection = (StatefulRedisConnection<String, String>) mock(StatefulRedisConnection.class);
        var async = (RedisAsyncCommands<String, String>) mock(RedisAsyncCommands.class);
        when(connection.async()).thenReturn(async);
        when(connection.isOpen()).thenReturn(true);
        when(connection.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.<List<Object>>evalsha(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(command);
        return connection;
    }

    @SuppressWarnings("unchecked")
    private static RedisFuture<List<Object>> result(List<Object> value) throws Exception {
        var future = (RedisFuture<List<Object>>) mock(RedisFuture.class);
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(value);
        return future;
    }

    private static List<?> execute(LimiterRedisConnection transport) {
        return transport.execute("sha", "source", "key", ARGS);
    }

    private static void unavailable(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RateLimitException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(RateLimitException.Reason.UNAVAILABLE);
            assertThat(failure).hasMessage("Rate limiting unavailable").hasNoCause();
        });
    }

    @Test
    void constructorIsLazyAndCloseOwnsResourcesExactlyOnce() {
        var connector = new Connector();
        var transport = new LimiterRedisConnection(ticker::get, connector);
        assertThat(connector.calls).hasValue(0);
        transport.close();
        transport.close();
        unavailable(() -> execute(transport));
        assertThat(connector.calls).hasValue(0);
        assertThat(connector.closes).isEqualTo(1);
    }

    @Test
    void productionOptionsAreReadFromActualClientAndResourcesAreBounded() {
        try (var transport = new LimiterRedisConnection(RedisURI.create("redis://localhost:1"))) {
            Object connector = ReflectionTestUtils.getField(transport, "connector");
            RedisClient client = (RedisClient) ReflectionTestUtils.getField(connector, "client");
            var options = client.getOptions();
            assertThat(options.isAutoReconnect()).isFalse();
            assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
            assertThat(options.getRequestQueueSize()).isEqualTo(128);
            assertThat(options.getTimeoutOptions().isTimeoutCommands()).isTrue();
            assertThat(options.getTimeoutOptions().getSource().getTimeout(null)).isEqualTo(2_000_000_000L);
            assertThat(client.getResources().ioThreadPoolSize()).isEqualTo(2);
            assertThat(client.getResources().computationThreadPoolSize()).isEqualTo(2);
        }
    }

    @Test
    void concurrentConnectIsRejectedAndShutdownClosesLateCompletion() throws Exception {
        var connector = new Connector();
        var attempt = new Attempt();
        connector.attempts.add(attempt);
        var transport = new LimiterRedisConnection(ticker::get, connector);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> execute(transport));
            await(() -> connector.calls.get() == 1);
            unavailable(() -> execute(transport));
            assertThat(connector.calls).hasValue(1);
            transport.close();
            var late = connection(result(OK));
            attempt.result.complete(late);
            attempt.closed.complete(null);
            assertThatThrownBy(() -> first.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(RateLimitException.class);
            verify(late, atLeastOnce()).closeAsync();
            verifyNoInteractions(late.async());
        }
    }

    @Test
    void failedConnectHasOneSecondCooldownAndNeverRetainsSecrets() {
        var connector = new Connector();
        var attempt = new Attempt();
        attempt.result.completeExceptionally(new RedisConnectionException("private URI password"));
        attempt.closed.complete(null);
        connector.attempts.add(attempt);
        try (var transport = new LimiterRedisConnection(ticker::get, connector)) {
            unavailable(() -> execute(transport));
            ticker.set(999_999_999L);
            unavailable(() -> execute(transport));
            assertThat(connector.calls).hasValue(1);
            ticker.set(1_000_000_000L);
            connector.attempts.add(attempt);
            unavailable(() -> execute(transport));
            assertThat(connector.calls).hasValue(2);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyTypedNoScriptFallsBackOnceWithSameImmutableArgumentsAndRemainingBudget() throws Exception {
        var sha = (RedisFuture<List<Object>>) mock(RedisFuture.class);
        when(sha.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
            assertThat((long) invocation.getArgument(0)).isEqualTo(1_500_000_000L);
            ticker.addAndGet(600_000_000L);
            throw new ExecutionException(new RedisNoScriptException("NOSCRIPT"));
        });
        var eval = result(OK);
        when(eval.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
            assertThat((long) invocation.getArgument(0)).isEqualTo(900_000_000L);
            return OK;
        });
        var connection = connection(sha);
        when(connection.async().<List<Object>>eval(eq("source"), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(eval);
        var connector = new Connector();
        var connected = new Attempt();
        connected.result.complete(connection);
        connector.attempts.add(connected);
        // Connect initiation consumes part of the same monotonic budget.
        try (var transport = new LimiterRedisConnection(ticker::get, new LimiterRedisConnection.Connector() {
            public LimiterRedisConnection.ConnectAttempt connect() { ticker.addAndGet(500_000_000L); return connector.connect(); }
            public void close() { connector.close(); }
        })) {
            var mutable = new ArrayList<>(ARGS);
            assertThat(transport.execute("sha", "source", "key", mutable)).isEqualTo(OK);
            verify(connection.async()).evalsha("sha", ScriptOutputType.MULTI, new String[] {"key"}, ARGS.toArray(String[]::new));
            verify(connection.async()).eval("source", ScriptOutputType.MULTI, new String[] {"key"}, ARGS.toArray(String[]::new));
        }
    }

    @Test
    void otherErrorsNeverRetryAndPermitsAreReleased() throws Exception {
        var command = result(OK);
        when(command.get(anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenThrow(new ExecutionException(new RedisCommandExecutionException("NOSCRIPT private misleading text")));
        var connection = connection(command);
        var connector = new Connector();
        for (int i = 0; i < 100; i++) {
            var attempt = new Attempt();
            attempt.result.complete(connection);
            attempt.closed.complete(null);
            connector.attempts.add(attempt);
        }
        try (var transport = new LimiterRedisConnection(ticker::get, connector)) {
            for (int i = 0; i < 100; i++) unavailable(() -> execute(transport));
            verify(connection.async(), times(100)).evalsha(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class));
            verify(connection.async(), never()).eval(anyString(), any(), any(String[].class), any(String[].class));
        }
    }

    @Test
    void sixtyFifthCallIsImmediateUnavailableAndAllTimeoutPermitsRecover() throws Exception {
        var command = result(OK);
        var entered = new CountDownLatch(64);
        var release = new CountDownLatch(1);
        var blocking = new AtomicBoolean(false);
        when(command.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
            if (!blocking.get()) return OK;
            entered.countDown();
            assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            throw new TimeoutException();
        });
        var old = connection(command);
        var connector = new Connector();
        var attempt = new Attempt();
        attempt.result.complete(old);
        connector.attempts.add(attempt);
        try (var transport = new LimiterRedisConnection(ticker::get, connector);
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = new ArrayList<Future<?>>();
            assertThat(execute(transport)).isEqualTo(OK);
            blocking.set(true);
            for (int i = 0; i < 64; i++) calls.add(pool.submit(() -> unavailable(() -> execute(transport))));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            unavailable(() -> execute(transport));
            assertThat(System.nanoTime() - start).isLessThan(100_000_000L);
            release.countDown();
            for (var call : calls) call.get(2, TimeUnit.SECONDS);
            verify(command, atLeastOnce()).cancel(true);
            var fresh = new Attempt();
            fresh.result.complete(connection(result(OK)));
            connector.attempts.add(fresh);
            assertThat(execute(transport)).isEqualTo(OK);
        } finally { release.countDown(); }
    }

    @Test
    void connectionIsNotPublishedOrUsedBeforeInitializationFinishes() throws Exception {
        var connector = new Connector();
        var attempt = new Attempt();
        connector.attempts.add(attempt);
        var command = result(OK);
        var connection = connection(command);
        var initializing = new CountDownLatch(1);
        var initialized = new CountDownLatch(1);
        doAnswer(invocation -> {
            initializing.countDown();
            assertThat(initialized.await(1, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(connection).setAutoFlushCommands(true);
        try (var transport = new LimiterRedisConnection(ticker::get, connector);
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var call = pool.submit(() -> execute(transport));
            await(() -> connector.calls.get() == 1);
            // Complete on a different thread: CompletableFuture may wake get() before callbacks finish.
            var completion = pool.submit(() -> attempt.result.complete(connection));
            assertThat(initializing.await(1, TimeUnit.SECONDS)).isTrue();
            unavailable(() -> execute(transport));
            verifyNoInteractions(connection.async());
            initialized.countDown();
            assertThat(call.get(1, TimeUnit.SECONDS)).isEqualTo(OK);
            completion.get(1, TimeUnit.SECONDS);
        } finally { initialized.countDown(); }
    }

    @Test
    void timeoutWaitsForClosureBeforeReplacementWithoutWaitingOnCallerThread() throws Exception {
        var command = result(OK);
        when(command.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException());
        var old = connection(command);
        var close = new CompletableFuture<Void>();
        when(old.closeAsync()).thenReturn(close);
        var connector = new Connector();
        var first = new Attempt();
        first.result.complete(old);
        connector.attempts.add(first);
        var fresh = new Attempt();
        var newConnection = connection(result(OK));
        fresh.result.complete(newConnection);
        connector.attempts.add(fresh);
        try (var transport = new LimiterRedisConnection(ticker::get, connector)) {
            long start = System.nanoTime();
            unavailable(() -> execute(transport));
            unavailable(() -> execute(transport));
            assertThat(System.nanoTime() - start).isLessThan(100_000_000L);
            assertThat(connector.calls).hasValue(1);
            close.complete(null);
            assertThat(execute(transport)).isEqualTo(OK);
            verify(command).cancel(true);
            assertThat(connector.calls).hasValue(2);
        }
    }

    @Test
    void lateTimedOutConnectAndItsCloseCallbackCannotClearNewGeneration() throws Exception {
        var connector = new Connector();
        var old = new Attempt(new CompletableFuture<>() {
            @Override
            public StatefulRedisConnection<String, String> get(long timeout, TimeUnit unit) throws TimeoutException {
                assertThat(unit).isEqualTo(TimeUnit.NANOSECONDS);
                assertThat(timeout).isEqualTo(2_000_000_000L);
                throw new TimeoutException();
            }
        });
        connector.attempts.add(old);
        var fresh = new Attempt();
        fresh.result.complete(connection(result(OK)));
        connector.attempts.add(fresh);
        try (var transport = new LimiterRedisConnection(ticker::get, connector)) {
            unavailable(() -> execute(transport));
            assertThat(old.aborts).isEqualTo(1);
            unavailable(() -> execute(transport));
            old.closed.complete(null);
            ticker.set(1_000_000_000L);
            assertThat(execute(transport)).isEqualTo(OK);
            var late = connection(result(OK));
            var lateClose = new CompletableFuture<Void>();
            when(late.closeAsync()).thenReturn(lateClose);
            old.result.complete(late);
            verify(late).closeAsync();
            verifyNoInteractions(late.async());
            lateClose.complete(null);
            assertThat(execute(transport)).isEqualTo(OK);
            assertThat(connector.calls).hasValue(2);
        }
    }

    @Test
    void shutdownDuringConnectInitiationAbortsTheAttemptWhenItBecomesAvailable() throws Exception {
        var started = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var attempt = new Attempt();
        var connector = new LimiterRedisConnection.Connector() {
            public LimiterRedisConnection.ConnectAttempt connect() {
                started.countDown();
                try { assertThat(proceed.await(1, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return attempt;
            }
            public void close() { }
        };
        try (var transport = new LimiterRedisConnection(ticker::get, connector);
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var call = pool.submit(() -> unavailable(() -> execute(transport)));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            transport.close();
            proceed.countDown();
            call.get(1, TimeUnit.SECONDS);
            assertThat(attempt.aborts).isEqualTo(1);
        } finally { proceed.countDown(); attempt.closed.complete(null); }
    }

    @Test
    void expiredNoScriptBudgetDoesNotDispatchFallback() throws Exception {
        var command = result(OK);
        when(command.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
            ticker.set(2_000_000_000L);
            throw new ExecutionException(new RedisNoScriptException("NOSCRIPT"));
        });
        var connection = connection(command);
        var connector = new Connector();
        var attempt = new Attempt();
        attempt.result.complete(connection);
        connector.attempts.add(attempt);
        try (var transport = new LimiterRedisConnection(ticker::get, connector)) {
            unavailable(() -> execute(transport));
            verify(connection.async(), never()).eval(anyString(), any(), any(String[].class), any(String[].class));
            verify(connection).closeAsync();
        }
    }

    @Test
    void enabledWiringUsesStandaloneSettingsAndBeanDestructionClosesOwnedResources() {
        var saved = new AtomicReference<LimiterRedisConnection>();
        new ApplicationContextRunner().withUserConfiguration(RateLimitConfiguration.class)
                .withPropertyValues("spring.data.redis.host=redis.example", "spring.data.redis.port=16379",
                        "spring.data.redis.database=4", "spring.data.redis.username=private-user",
                        "spring.data.redis.password=private-password", "spring.data.redis.ssl.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(LimiterRedisConnection.class).hasSingleBean(TokenBucketStore.class);
                    var transport = context.getBean(LimiterRedisConnection.class);
                    saved.set(transport);
                    var connector = ReflectionTestUtils.getField(transport, "connector");
                    var uri = (RedisURI) ReflectionTestUtils.getField(connector, "uri");
                    assertThat(uri.getHost()).isEqualTo("redis.example");
                    assertThat(uri.getPort()).isEqualTo(16379);
                    assertThat(uri.getDatabase()).isEqualTo(4);
                    var credentials = uri.getCredentialsProvider().resolveCredentials().block(Duration.ofSeconds(1));
                    assertThat(credentials.getUsername()).isEqualTo("private-user");
                    assertThat(new String(credentials.getPassword())).isEqualTo("private-password");
                    assertThat(uri.isSsl()).isTrue();
                    assertThat(ReflectionTestUtils.getField(transport, "current")).isInstanceOfSatisfying(AtomicReference.class,
                            current -> assertThat(current.get()).isNull());
                });
        unavailable(() -> execute(saved.get()));
        assertThat(((AtomicBoolean) ReflectionTestUtils.getField(saved.get(), "closed"))).isTrue();
    }

    @Test
    void effectiveConnectionDetailsOverrideStandaloneProperties() {
        var details = new DataRedisConnectionDetails() {
            public Standalone getStandalone() { return Standalone.of("effective.example", 26379, 7); }
            public String getUsername() { return "effective-user"; }
            public String getPassword() { return "effective-password"; }
        };
        new ApplicationContextRunner().withUserConfiguration(RateLimitConfiguration.class)
                .withBean(DataRedisConnectionDetails.class, () -> details)
                .withPropertyValues("spring.data.redis.host=ignored.example", "spring.data.redis.password=ignored-password")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Object connector = ReflectionTestUtils.getField(context.getBean(LimiterRedisConnection.class), "connector");
                    RedisURI uri = (RedisURI) ReflectionTestUtils.getField(connector, "uri");
                    assertThat(uri.getHost()).isEqualTo("effective.example");
                    assertThat(uri.getPort()).isEqualTo(26379);
                    assertThat(uri.getDatabase()).isEqualTo(7);
                    var credentials = uri.getCredentialsProvider().resolveCredentials().block(Duration.ofSeconds(1));
                    assertThat(credentials.getUsername()).isEqualTo("effective-user");
                    assertThat(new String(credentials.getPassword())).isEqualTo("effective-password");
                });
    }

    @Test
    void unsupportedRedisModesFailExplicitlyWithoutDisclosingSecretsAndDisabledCreatesNoTransport() {
        for (String setting : List.of("spring.data.redis.url=redis://private-user:private-password@private-host:6379",
                "spring.data.redis.cluster.nodes[0]=private-host:6379", "spring.data.redis.sentinel.master=private-master",
                "spring.data.redis.masterreplica.nodes[0]=private-host:6379", "spring.data.redis.ssl.bundle=private-bundle")) {
            var runner = new ApplicationContextRunner().withUserConfiguration(RateLimitConfiguration.class)
                    .withPropertyValues(setting);
            runner.run(context -> {
                assertThat(context).hasFailed();
                var failure = context.getStartupFailure();
                while (failure.getCause() != null) failure = failure.getCause();
                assertThat(failure).hasMessage("Unsupported limiter Redis configuration").hasNoCause();
            });
            runner.withPropertyValues("app.rate-limit.enabled=false").run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(LimiterRedisConnection.class)
                        .doesNotHaveBean(TokenBucketStore.class).doesNotHaveBean(RedisClient.class);
            });
        }
    }

    @Test
    void connectionBoundIsJvmWideAndReplacementWaitsForExactCloseAcknowledgement() throws Exception {
        var firstConnector = new Connector();
        var firstAttempt = new Attempt();
        var firstConnection = connection(result(OK));
        var closing = new CompletableFuture<Void>();
        when(firstConnection.closeAsync()).thenReturn(closing);
        firstAttempt.result.complete(firstConnection);
        firstConnector.attempts.add(firstAttempt);
        var secondConnector = new Connector();
        var secondAttempt = new Attempt();
        secondAttempt.result.complete(connection(result(OK)));
        secondConnector.attempts.add(secondAttempt);
        try (var first = new LimiterRedisConnection(ticker::get, firstConnector);
                var second = new LimiterRedisConnection(ticker::get, secondConnector)) {
            assertThat(execute(first)).isEqualTo(OK);
            unavailable(() -> execute(second));
            assertThat(secondConnector.calls).hasValue(0);
            first.close();
            unavailable(() -> execute(second));
            assertThat(secondConnector.calls).hasValue(0);
            closing.complete(null);
            assertThat(execute(second)).isEqualTo(OK);
            // An old completion cannot release the replacement's JVM connection lease twice.
            first.close();
            assertThat(execute(second)).isEqualTo(OK);
            assertThat(secondConnector.calls).hasValue(1);
        } finally { closing.complete(null); }
    }

    @Test
    void cancelledHandshakeWithoutChannelStillAcknowledgesTerminalConnectFailure() throws Exception {
        var attempt = new LettuceLimiterConnector.Attempt();
        var closure = attempt.abort();
        assertThat(attempt.future()).isCancelled();
        assertThat(closure).isNotDone();
        // A DNS/connect failure can arrive after cancellation, without ever initializing a channel.
        attempt.failed();
        assertThat(closure).isCompleted();
    }

    @Test
    void directConstructorRejectsSentinelAndSocketUrisBeforeCreatingResources() {
        for (RedisURI uri : List.of(RedisURI.Builder.sentinel("localhost", 26379, "private-master").build(),
                RedisURI.Builder.socket("private-socket").build())) {
            assertThatThrownBy(() -> new LimiterRedisConnection(uri)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unsupported limiter Redis configuration").hasNoCause();
        }
    }

    @Test
    void uriWiringPreservesRawAclUsernameAndPassword() {
        var properties = new org.springframework.boot.data.redis.autoconfigure.DataRedisProperties();
        properties.setUsername(" ");
        properties.setPassword(" private-password ");
        var uri = RateLimitConfiguration.limiterRedisUri(properties, null);
        var credentials = uri.getCredentialsProvider().resolveCredentials().block(Duration.ofSeconds(1));
        assertThat(credentials.getUsername()).isEqualTo(" ");
        assertThat(new String(credentials.getPassword())).isEqualTo(" private-password ");
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).isLessThan(end);
            Thread.sleep(1);
        }
    }
}
