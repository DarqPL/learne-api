package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RateLimitServiceTests {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RateLimitPolicy policy = new RateLimitPolicy(RateLimitDimension.IP, "r1", 5, 5, Duration.ofSeconds(1));
    private final RateLimitProperties properties = new RateLimitProperties(true, null, null, null, null, null,
            Map.of("login-ip", policy));
    private final TokenBucketStore store = mock(TokenBucketStore.class);
    @SuppressWarnings("unchecked")
    private final Supplier<TokenBucketStore> supplier = mock(Supplier.class);
    private final RateLimitMetrics metrics = new RateLimitMetrics(registry, properties.policies().keySet());

    @AfterEach
    void closeRegistry() { registry.close(); }

    private RateLimitService service() {
        when(supplier.get()).thenReturn(store);
        return new RateLimitService(properties, new RateLimitKeyFactory(properties), supplier, metrics);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void returnsStoreDecisionAndRecordsExactlyOnce(boolean allowed) {
        var expected = allowed ? RateLimitDecision.allowed() : RateLimitDecision.rejected(1234);
        when(store.consume(anyString(), eq(policy))).thenReturn(expected);
        assertThat(service().consume("login-ip", RateLimitDimension.IP, "192.0.2.1")).isEqualTo(expected);
        verify(store).consume(new RateLimitKeyFactory(properties).key("login-ip", policy, RateLimitDimension.IP, "192.0.2.1"), policy);
        verify(supplier).get();
        assertRecorded("login-ip", allowed ? "allowed" : "rejected", 1);
    }

    @Test
    void requireAllowedRejectsGenericallyWithRetryAndCountsOnlyOnce() {
        when(store.consume(anyString(), any())).thenReturn(RateLimitDecision.rejected(2345));
        assertThatThrownBy(() -> service().requireAllowed("login-ip", RateLimitDimension.IP, "192.0.2.1"))
                .isInstanceOfSatisfying(RateLimitException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(RateLimitException.Reason.REJECTED);
                    assertThat(failure.retryDelayMs()).isEqualTo(2345);
                }).hasMessage("Rate limit exceeded").hasNoCause();
        assertRecorded("login-ip", "rejected", 1);
    }

    @Test
    void requireAllowedContinuesForAllowed() {
        when(store.consume(anyString(), any())).thenReturn(RateLimitDecision.allowed());
        assertThatCode(() -> service().requireAllowed("login-ip", RateLimitDimension.IP, "192.0.2.1"))
                .doesNotThrowAnyException();
        assertRecorded("login-ip", "allowed", 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"supplier", "store", "rejected-exception", "null-store", "null-decision", "bypassed"})
    void dependencyFailuresAndBogusDecisionsAreUnavailable(String mode) {
        var service = service();
        switch (mode) {
            case "supplier" -> when(supplier.get()).thenThrow(new IllegalStateException("private-email@example.com"));
            case "store" -> when(store.consume(anyString(), any())).thenThrow(new IllegalStateException(properties.hmacSecret()));
            case "rejected-exception" -> when(store.consume(anyString(), any())).thenThrow(RateLimitException.rejected(100));
            case "null-store" -> when(supplier.get()).thenReturn(null);
            case "null-decision" -> when(store.consume(anyString(), any())).thenReturn(null);
            case "bypassed" -> when(store.consume(anyString(), any())).thenReturn(RateLimitDecision.bypassed());
        }
        assertUnavailable(() -> service.requireAllowed("login-ip", RateLimitDimension.IP, "192.0.2.1"));
        assertRecorded("login-ip", "unavailable", 1);
    }

    @ParameterizedTest
    @CsvSource(value = {"<null>,IP,192.0.2.1", "unknown,IP,192.0.2.1", "login-ip,<null>,192.0.2.1",
            "login-ip,EMAIL,private@example.com", "login-ip,IP,<null>", "login-ip,IP,hostname.example",
            "login-ip,IP,''"}, nullValues = "<null>")
    void validatesBeforeSupplierAccess(String id, RateLimitDimension dimension, String identifier) {
        var service = service();
        assertUnavailable(() -> service.consume(id, dimension, identifier));
        verifyNoInteractions(supplier, store);
        assertRecorded("login-ip".equals(id) ? "login-ip" : "configuration", "unavailable", 1);
    }

    @Test
    void disabledBypassesUnknownAndNullInputsWithoutAnyDependencies() {
        var disabled = new RateLimitProperties(false, null, null, "", null, null, Map.of());
        var keys = mock(RateLimitKeyFactory.class);
        var service = new RateLimitService(disabled, keys, supplier, metrics);
        assertThat(service.consume("private@example.com", null, null)).isEqualTo(RateLimitDecision.bypassed());
        assertThatCode(() -> service.requireAllowed(null, null, null)).doesNotThrowAnyException();
        verifyNoInteractions(keys, supplier, store);
        assertRecorded("configuration", "bypassed", 2);
        assertThat(registry.find("learne.ratelimit.requests").tag("outcome", "allowed").counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void unknownCallerIdsCannotExpandMetricLabelsOrLeakInputs() {
        var service = service();
        int series = registry.getMeters().size();
        for (int i = 0; i < 100; i++) {
            String id = "private-email-" + java.util.UUID.randomUUID() + "@example.com";
            assertUnavailable(() -> service.consume(id, RateLimitDimension.IP, "192.0.2.1"));
        }
        assertRecorded("configuration", "unavailable", 100);
        assertThat(registry.getMeters()).hasSize(series);
        assertThat(registry.getMeters()).allSatisfy(meter -> {
            if (meter.getId().getName().equals("learne.ratelimit.client-ip.fallback")) {
                assertThat(meter.getId().getTags()).hasSize(1);
                assertThat(meter.getId().getTag("reason")).isIn("missing_header", "malformed_header",
                        "header_too_large", "too_many_hops", "all_trusted");
                return;
            }
            assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                assertThat(tag.getKey()).isIn("policy", "outcome");
                assertThat(tag.getValue()).isIn("configuration", "login-ip", "allowed", "rejected", "unavailable", "bypassed");
            });
        });
        verifyNoInteractions(supplier, store);
    }

    @Test
    void metricPolicyKeysAreDefensivelyCopied() {
        var ids = new HashSet<>(Set.of("login-ip"));
        var isolated = new SimpleMeterRegistry();
        try {
            var copied = new RateLimitMetrics(isolated, ids);
            ids.clear();
            var service = new RateLimitService(properties, new RateLimitKeyFactory(properties), () -> store, copied);
            when(store.consume(anyString(), any())).thenReturn(RateLimitDecision.allowed());
            service.consume("login-ip", RateLimitDimension.IP, "192.0.2.1");
            assertThat(isolated.get("learne.ratelimit.requests").tags("policy", "login-ip", "outcome", "allowed")
                    .counter().count()).isEqualTo(1);
        } finally { isolated.close(); }
    }

    @Test
    void durationIncludesStoreExecution() {
        when(store.consume(anyString(), any())).thenAnswer(invocation -> {
            Thread.sleep(20);
            return RateLimitDecision.allowed();
        });
        service().consume("login-ip", RateLimitDimension.IP, "192.0.2.1");
        assertThat(registry.get("learne.ratelimit.duration").tags("policy", "login-ip", "outcome", "allowed")
                .timer().totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"properties", "metrics"})
    void missingRequiredConfigurationIsGenericUnavailable(String missing) {
        assertUnavailable(() -> new RateLimitService(missing.equals("properties") ? null : properties,
                new RateLimitKeyFactory(properties), supplier, missing.equals("metrics") ? null : metrics));
        verifyNoInteractions(supplier, store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"keys", "supplier"})
    void missingEnabledDependencyIsUnavailableWithoutStoreAccess(String missing) {
        var service = new RateLimitService(properties, missing.equals("keys") ? null : new RateLimitKeyFactory(properties),
                missing.equals("supplier") ? null : supplier, metrics);
        assertUnavailable(() -> service.consume("login-ip", RateLimitDimension.IP, "192.0.2.1"));
        assertRecorded("login-ip", "unavailable", 1);
        verifyNoInteractions(store);
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RateLimitException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(RateLimitException.Reason.UNAVAILABLE);
            assertThat(failure.retryDelayMs()).isZero();
        }).hasMessage("Rate limiting unavailable").hasNoCause();
    }

    private void assertRecorded(String policy, String outcome, long count) {
        assertThat(registry.get("learne.ratelimit.requests").tags("policy", policy, "outcome", outcome)
                .counter().count()).isEqualTo(count);
        assertThat(registry.get("learne.ratelimit.duration").tags("policy", policy, "outcome", outcome)
                .timer().count()).isEqualTo(count);
        assertThat(registry.find("learne.ratelimit.requests").counters().stream().mapToDouble(c -> c.count()).sum())
                .isEqualTo(count);
        assertThat(registry.find("learne.ratelimit.duration").timers().stream().mapToLong(t -> t.count()).sum())
                .isEqualTo(count);
    }
}
