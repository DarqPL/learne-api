package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RateLimitKeyFactoryTests {
    private final RateLimitKeyFactory factory = new RateLimitKeyFactory(props("local", null, true));

    private static RateLimitProperties props(String environment, String secret, boolean enabled) {
        return new RateLimitProperties(enabled, null, environment, secret, null, null, null);
    }

    private static RateLimitPolicy policy(RateLimitDimension dimension, String revision) {
        return new RateLimitPolicy(dimension, revision, 1, 1, Duration.ofSeconds(1));
    }

    private String key(RateLimitDimension dimension, String identifier) {
        return factory.key("login", policy(dimension, "r1"), dimension, identifier);
    }

    @Test
    void goldenEmailHmacAndNoRawIdentifier() {
        assertThat(key(RateLimitDimension.EMAIL, " Alice@Example.COM ")).isEqualTo(
                "learne:local:rl:v1:login:r1:email:55be01d4a4edae89079d10e964ed5ffbbc959d6a833d5c9eefea317dc37b6bc0");
        assertThat(key(RateLimitDimension.EMAIL, "alice@example.com")).doesNotContain("alice", "example.com", RateLimitProperties.LOCAL_TEST_SECRET);
    }

    @Test
    void normalizesEachDimensionWithoutEmailAliasRewriting() {
        assertThat(key(RateLimitDimension.IP, "::ffff:192.0.2.1")).isEqualTo(key(RateLimitDimension.IP, "192.0.2.1"));
        assertThat(key(RateLimitDimension.IP, "2001:0DB8:0:0:0:0:0:1")).isEqualTo(key(RateLimitDimension.IP, "2001:db8::1"));
        assertThat(key(RateLimitDimension.USER, "00042")).isEqualTo(key(RateLimitDimension.USER, "42"));
        assertThat(key(RateLimitDimension.USER, Long.toString(Long.MAX_VALUE))).matches(".*:user:[0-9a-f]{64}");
        assertThat(key(RateLimitDimension.EMAIL, "a.lice+tag@example.com")).isNotEqualTo(key(RateLimitDimension.EMAIL, "alice@example.com"));
        assertThat(key(RateLimitDimension.EMAIL, "é".repeat(127))).matches(".*:email:[0-9a-f]{64}");
    }

    @Test
    void environmentRevisionPolicyAndDimensionIsolation() {
        var email = policy(RateLimitDimension.EMAIL, "r1");
        String baseline = factory.key("login", email, RateLimitDimension.EMAIL, "42");
        assertThat(new RateLimitKeyFactory(props("test", null, true)).key("login", email, RateLimitDimension.EMAIL, "42")).isNotEqualTo(baseline);
        assertThat(factory.key("login", policy(RateLimitDimension.EMAIL, "r2"), RateLimitDimension.EMAIL, "42")).isNotEqualTo(baseline);
        assertThat(factory.key("other", email, RateLimitDimension.EMAIL, "42")).isNotEqualTo(baseline);
        assertThat(key(RateLimitDimension.USER, "42")).isNotEqualTo(baseline);
        assertThat(factory.key("a".repeat(48), email, RateLimitDimension.EMAIL, "42")).contains(":" + "a".repeat(48) + ":");
    }

    @Test
    void invalidCallersHaveOnlyGenericUnavailableErrors() {
        for (String input : List.of("", " ", "sensitive\0email", "é".repeat(128))) {
            unavailable(() -> key(RateLimitDimension.EMAIL, input));
        }
        for (String input : List.of("0", "-1", "+1", "1.0", " 1", "9223372036854775808", "sensitive-user")) {
            unavailable(() -> key(RateLimitDimension.USER, input));
        }
        for (String input : List.of("localhost", "sensitive-hostname", "[::1]")) unavailable(() -> key(RateLimitDimension.IP, input));
        for (String id : List.of("", "Bad", "a_", "a".repeat(49), "private\0id")) {
            unavailable(() -> factory.key(id, policy(RateLimitDimension.IP, "r1"), RateLimitDimension.IP, "192.0.2.1"));
        }
        unavailable(() -> factory.key(null, policy(RateLimitDimension.IP, "r1"), RateLimitDimension.IP, "192.0.2.1"));
        unavailable(() -> factory.key("login", null, RateLimitDimension.IP, "192.0.2.1"));
        unavailable(() -> factory.key("login", policy(RateLimitDimension.IP, "r1"), null, "192.0.2.1"));
        unavailable(() -> factory.key("login", policy(RateLimitDimension.IP, "r1"), RateLimitDimension.EMAIL, "private@example.com"));
        for (var dimension : RateLimitDimension.values()) unavailable(() -> key(dimension, null));
    }

    @Test
    void rejectsNulEvenWhenTrimWouldRemoveIt() {
        unavailable(() -> key(RateLimitDimension.EMAIL, "alice@example.com\0"));
        unavailable(() -> key(RateLimitDimension.EMAIL, "\0alice@example.com"));
    }

    @Test
    void disabledBlankSecretIsLazyAndRawSecretIsNotTrimmedOrDecoded() {
        var disabled = new RateLimitKeyFactory(props("test", "", false));
        unavailable(() -> disabled.key("login", policy(RateLimitDimension.EMAIL, "r1"), RateLimitDimension.EMAIL, "alice@example.com"));
        String secret = "  abcdefghijklmnopqrstuvwxyz123456  ";
        var raw = new RateLimitKeyFactory(props("local", secret, true));
        var trimmed = new RateLimitKeyFactory(props("local", secret.trim(), true));
        assertThat(raw.key("login", policy(RateLimitDimension.EMAIL, "r1"), RateLimitDimension.EMAIL, "alice@example.com"))
                .isNotEqualTo(trimmed.key("login", policy(RateLimitDimension.EMAIL, "r1"), RateLimitDimension.EMAIL, "alice@example.com"));
    }

    @Test
    void concurrentCallsAreDeterministicAndIsolated() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> calls = IntStream.range(0, 256).mapToObj(i -> (Callable<String>) () ->
                    key(RateLimitDimension.EMAIL, "user" + i % 17 + "@example.com")).toList();
            var results = executor.invokeAll(calls);
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get()).isEqualTo(key(RateLimitDimension.EMAIL, "user" + i % 17 + "@example.com"));
            }
        }
    }

    private void unavailable(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RateLimitException.class, error -> {
            assertThat(error.reason()).isEqualTo(RateLimitException.Reason.UNAVAILABLE);
            assertThat(error.getMessage()).isEqualTo("Rate limiting unavailable");
            assertThat(error.getCause()).isNull();
        });
    }
}
