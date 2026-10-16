package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RedisTokenBucketStoreTests {
    private static final RateLimitPolicy POLICY = new RateLimitPolicy(
            RateLimitDimension.values()[0], "r1", 3, 2, Duration.ofSeconds(1));

    @Test
    void passesStaticSourceShaKeyAndOrderedImmutableArguments() {
        TokenBucketStore store = new RedisTokenBucketStore((sha, source, key, args) -> {
            assertThat(source).contains("redis.call('TIME')", "redis.call('SET'");
            try {
                assertThat(sha).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                        .digest(source.getBytes(StandardCharsets.UTF_8))));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            assertThat(key).isEqualTo("opaque-bucket");
            assertThat(args).containsExactly("3", "2", "1000", POLICY.fingerprint());
            assertThatThrownBy(() -> args.add("extra")).isInstanceOf(UnsupportedOperationException.class);
            return List.of(1L, 0L);
        });
        assertThat(store.consume("opaque-bucket", POLICY)).isEqualTo(RateLimitDecision.allowed());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 500, 86460000})
    void decodesRejected(long retry) {
        assertThat(new RedisTokenBucketStore((sha, source, key, args) -> List.of(0L, retry))
                .consume("bucket", POLICY)).isEqualTo(RateLimitDecision.rejected(retry));
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void rejectsInvalidResultsWithoutDetails(List<?> result) {
        assertUnavailable(new RedisTokenBucketStore((sha, source, key, args) -> result));
    }

    static Stream<List<?>> invalidResults() {
        return Stream.of(null, List.of(), List.of(1L), List.of(1L, 0L, 0L), List.of(2L, 0L),
                List.of(-1L, 0L), List.of(1L, 1L), List.of(0L, 0L), List.of(0L, -1L),
                List.of(0L, 86460001L), List.of("1", "0"), List.of(1.0, 0.0),
                List.of(1, 0), Arrays.asList(null, 0L), Arrays.asList(1L, null));
    }

    @Test
    void sanitizesExecutorErrorsAndDoesNotRetry() {
        int[] calls = {0};
        assertUnavailable(new RedisTokenBucketStore((sha, source, key, args) -> {
            calls[0]++;
            throw new IllegalStateException("secret raw identifier and arguments");
        }));
        assertThat(calls[0]).isEqualTo(1);
    }

    private void assertUnavailable(TokenBucketStore store) {
        assertThatThrownBy(() -> store.consume("sensitive-key", POLICY))
                .isInstanceOfSatisfying(RateLimitException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(RateLimitException.Reason.UNAVAILABLE);
                    assertThat(exception.getMessage()).isEqualTo("Rate limiting unavailable");
                    assertThat(exception.getCause()).isNull();
                });
    }
}
