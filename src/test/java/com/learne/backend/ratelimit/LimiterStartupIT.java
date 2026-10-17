package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/** JVM liveness only: Compose dependency health/start ordering is a separate contract. */
@ActiveProfiles("test")
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.cache.redis.enabled=false",
        "app.rate-limit.enabled=true",
        "app.rate-limit.environment=test",
        "app.rate-limit.hmac-secret=task8-startup-independent-test-secret-32bytes",
        "app.rate-limit.policies.startup.dimension=IP",
        "app.rate-limit.policies.startup.revision=r1",
        "app.rate-limit.policies.startup.capacity=2",
        "app.rate-limit.policies.startup.refill-tokens=1",
        "app.rate-limit.policies.startup.refill-period=1s"
})
class LimiterStartupIT {
    // A bound client socket reserves the port without creating a listening server.
    private static final Socket NO_LISTENER = reservePort();

    private static Socket reservePort() {
        try {
            var socket = new Socket();
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void redisWithoutListener(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", NO_LISTENER::getLocalPort);
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.username", () -> "");
    }

    @AfterAll
    static void releasePort() throws Exception { NO_LISTENER.close(); }

    @Value("${local.server.port}") int port;
    @Autowired ApplicationContext context;
    @Autowired RateLimitProperties properties;
    @Autowired RateLimitService service;

    @AfterEach
    void awaitTransportShutdown() throws Exception {
        LimiterTestCleanup.closeAndAwait(context.getBean(LimiterRedisConnection.class));
    }

    @Test
    void startsRealServerLazilyAndKeepsHealthOkWhileLimiterCallsFailClosed() throws Exception {
        assertThat(properties.enabled()).isTrue();
        assertThat(context.getBean(RateLimitConfiguration.class)).isNotNull();
        assertThat(context.getBean(TokenBucketStore.class)).isExactlyInstanceOf(RedisTokenBucketStore.class);
        var transport = context.getBean(LimiterRedisConnection.class);
        assertNeverConnected(transport);
        healthIsOk();
        assertNeverConnected(transport);

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.requireAllowed("startup", RateLimitDimension.IP, "192.0.2.8"))
                .isInstanceOfSatisfying(RateLimitException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(RateLimitException.Reason.UNAVAILABLE);
                    assertThat(failure).hasNoCause().hasMessage("Rate limiting unavailable");
                    var response = RateLimitErrorResponseFactory.response(failure);
                    assertThat(response.getStatusCode().value()).isEqualTo(503);
                    assertThat(response.getHeaders().getFirst("Retry-After")).isNull();
                });
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
        assertThat(ReflectionTestUtils.getField(transport, "retryAt")).as("real failed connection attempt").isNotNull();
        healthIsOk();
    }

    private static void assertNeverConnected(LimiterRedisConnection transport) {
        assertThat(((AtomicReference<?>) ReflectionTestUtils.getField(transport, "current")).get()).isNull();
        assertThat(ReflectionTestUtils.getField(transport, "retryAt")).isNull();
    }

    private void healthIsOk() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).get("status").asText())
                    .isEqualTo("ok");
        }
    }
}
