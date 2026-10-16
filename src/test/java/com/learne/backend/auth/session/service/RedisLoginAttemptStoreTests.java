package com.learne.backend.auth.session.service;

import static org.assertj.core.api.Assertions.*;

import com.learne.backend.ratelimit.*;
import com.learne.backend.auth.session.dto.LoginRequest;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

class RedisLoginAttemptStoreTests {
    private static final RateLimitProperties PROPS = new RateLimitProperties(true, "login-test", "test", null, null, null, null);

    private static RedisLoginAttemptStore store(RedisTokenBucketStore.ScriptExecutor executor) {
        return new RedisLoginAttemptStore(PROPS, new RateLimitKeyFactory(PROPS), executor);
    }

    private static void call(RedisLoginAttemptStore store, String operation, String email) {
        switch (operation) {
            case "assertCanAttempt" -> store.assertCanAttempt(email);
            case "recordFailure" -> store.recordFailure(email);
            case "clear" -> store.clear(email);
            default -> throw new AssertionError(operation);
        }
    }

    @Test
    void operationsUseOnePrivateCanonicalKeyAndFixedImmutableArguments() throws Exception {
        var keys = new ArrayList<String>();
        var operations = new ArrayList<String>();
        var sources = new ArrayList<String>();
        var store = store((sha, source, key, args) -> {
            keys.add(key);
            operations.add(args.getFirst());
            sources.add(source);
            assertThat(args.subList(1, 3)).containsExactly("5", "900000");
            assertThatThrownBy(() -> args.add("mutable")).isInstanceOf(UnsupportedOperationException.class);
            try {
                assertThat(sha).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                        .digest(source.getBytes(StandardCharsets.UTF_8))));
            } catch (Exception exception) { throw new AssertionError(exception); }
            return List.of(1L, 0L);
        });
        call(store, "assertCanAttempt", " Alice@Example.COM ");
        call(store, "recordFailure", "alice@example.com");
        call(store, "clear", "ALICE@EXAMPLE.COM");
        assertThat(operations).containsExactly("check", "failure", "clear");
        assertThat(keys).containsOnly("login-test:test:auth:login-failures:v1:email:"
                + "55be01d4a4edae89079d10e964ed5ffbbc959d6a833d5c9eefea317dc37b6bc0");
        assertThat(sources.get(0)).isSameAs(sources.get(1)).isSameAs(sources.get(2));
    }

    @Test
    void unicodeAbove254Utf8BytesIsAcceptedButInvalidEmailsNeverReachRedis() throws Exception {
        int[] calls = {0};
        var store = store((sha, source, key, args) -> { calls[0]++; return List.of(1L, 0L); });
        String unicode = "é".repeat(32) + "@" + ("é".repeat(30) + ".").repeat(3) + "é".repeat(30);
        assertThat(unicode.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(254);
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            assertThat(validation.getValidator().validate(new LoginRequest(unicode, "password"))).isEmpty();
        }
        call(store, "assertCanAttempt", unicode);
        for (String input : Arrays.asList(null, "", "  ", "\0alice@example.com", "alice@example.com\0", "\u2003")) {
            unavailable(() -> call(store, "recordFailure", input));
        }
        assertThat(calls[0]).isEqualTo(1);
    }

    static List<List<?>> malformed() {
        return Arrays.asList(null, List.of(), List.of(1L), List.of(1L, 0L, 0L), List.of(1, 0),
                Arrays.asList(null, 0L), Arrays.asList(1L, null), List.of("1", "0"), List.of(2L, 0L),
                List.of(1L, 1L), List.of(0L, 0L), List.of(0L, -1L), List.of(0L, 86460001L));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void malformedResponsesFailClosedForEveryOperation(List<?> result) throws Exception {
        var store = store((sha, source, key, args) -> result);
        for (String operation : List.of("assertCanAttempt", "recordFailure", "clear")) {
            unavailable(() -> call(store, operation, "private@example.com"));
        }
    }

    @Test
    void transportFailuresAreCauseFreeAndNeverRetriedIncludingRejectedTransportErrors() throws Exception {
        for (RuntimeException failure : List.of(new IllegalStateException("private@example.com secret"),
                RateLimitException.rejected(1))) {
            int[] calls = {0};
            var store = store((sha, source, key, args) -> { calls[0]++; throw failure; });
            for (String operation : List.of("assertCanAttempt", "recordFailure", "clear")) {
                unavailable(() -> call(store, operation, "private@example.com"));
            }
            assertThat(calls[0]).isEqualTo(3);
        }
    }

    @Test
    void validDenialsRetainBoundsAndReachRealHttpWriterWhileZeroIsUnavailable() throws Exception {
        for (long retry : new long[] {1, 900000, 86460000}) {
            var store = store((sha, source, key, args) -> List.of(0L, retry));
            for (String operation : List.of("assertCanAttempt", "recordFailure", "clear")) {
                assertThatThrownBy(() -> call(store, operation, "private@example.com"))
                        .isInstanceOfSatisfying(RateLimitException.class, error -> {
                            assertThat(error.reason()).isEqualTo(RateLimitException.Reason.REJECTED);
                            assertThat(error.retryDelayMs()).isEqualTo(retry);
                            assertThat(error.getCause()).isNull();
                        });
            }
        }
        for (long retry : new long[] {0, 1}) {
            var store = store((sha, source, key, args) -> List.of(0L, retry));
            var response = new MockHttpServletResponse();
            try { call(store, "assertCanAttempt", "private@example.com"); fail("expected rejection"); }
            catch (RateLimitException error) { new RateLimitErrorResponseFactory(new ObjectMapper()).write(response, error); }
            assertThat(response.getStatus()).isEqualTo(retry == 0 ? 503 : 429);
            assertThat(response.getHeader("Retry-After")).isEqualTo(retry == 0 ? null : "1");
        }
    }

    @Test
    void componentIsEnabledByDefaultAndConditionallyDisabled() throws Exception {
        assertThat(RedisLoginAttemptStore.class.getAnnotation(Component.class)).isNotNull();
        var condition = RedisLoginAttemptStore.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition.prefix()).isEqualTo("app.rate-limit");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }

    private static void unavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RateLimitException.class, error -> {
            assertThat(error.reason()).isEqualTo(RateLimitException.Reason.UNAVAILABLE);
            assertThat(error.getMessage()).isEqualTo("Rate limiting unavailable");
            assertThat(error.getCause()).isNull();
        });
    }
}
