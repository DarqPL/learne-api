package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RateLimitPolicyTests {
    @Test
    void usesCanonicalFingerprintAndIntegerCredits() {
        var policy = policy("r1", 15, 60, Duration.ofMinutes(1));
        assertThat(policy.periodMs()).isEqualTo(60000);
        assertThat(policy.capacityCredit()).isEqualTo(900000);
        assertThat(policy.fingerprint()).isEqualTo("2d0df3f07bc1be26179c2b3f0cab3e5728ca632732d6178bba2686a368029dd5");
        assertThat(policy("r2", 15, 60, Duration.ofSeconds(60)).fingerprint()).isEqualTo(policy.fingerprint());
        assertThat(new RateLimitPolicy(RateLimitDimension.EMAIL, "r1", 15, 60, Duration.ofMinutes(1)).fingerprint())
                .isNotEqualTo(policy.fingerprint());
        assertThat(RateLimitDimension.IP.wire()).isEqualTo("ip");
        assertThat(RateLimitDimension.EMAIL.wire()).isEqualTo("email");
        assertThat(RateLimitDimension.USER.wire()).isEqualTo("user");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "r0", "r01", "R1", "r1000000000", "r1\n"})
    void rejectsInvalidRevision(String revision) {
        assertThatIllegalArgumentException().isThrownBy(() -> policy(revision, 1, 1, Duration.ofSeconds(1)));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 1000001, Long.MAX_VALUE})
    void rejectsOutOfRangeTokenCounts(long count) {
        assertThatIllegalArgumentException().isThrownBy(() -> policy("r1", count, 1, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> policy("r1", 1, count, Duration.ofSeconds(1)));
    }

    @Test
    void validatesExactMillisecondsAndFullRefillBoundaries() {
        for (Duration period : new Duration[] {Duration.ZERO, Duration.ofMillis(999), Duration.ofMillis(86400001),
                Duration.ofSeconds(1).plusNanos(1), Duration.ofSeconds(Long.MAX_VALUE)}) {
            assertThatIllegalArgumentException().isThrownBy(() -> policy("r1", 1, 1, period));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> policy("r1", 1, 1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitPolicy(null, "r1", 1, 1, Duration.ofSeconds(1)));
        assertThatCode(() -> policy("r999999999", 1000000, 1000000, Duration.ofDays(1))).doesNotThrowAnyException();
        assertThatCode(() -> policy("r1", 86400, 1, Duration.ofSeconds(1))).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> policy("r1", 86401, 1, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> policy("r1", 3, 2, Duration.ofMillis(57600001)));
    }

    @Test
    void decisionsAndExceptionsEnforceSafeRetryContract() {
        assertThat(RateLimitDecision.allowed()).isEqualTo(new RateLimitDecision(RateLimitDecision.Outcome.ALLOWED, 0));
        assertThat(RateLimitDecision.bypassed().outcome()).isEqualTo(RateLimitDecision.Outcome.BYPASSED);
        assertThat(RateLimitDecision.bypassed().retryDelayMs()).isZero();
        assertThat(RateLimitDecision.rejected(86460000).retryDelayMs()).isEqualTo(86460000);
        for (long delay : new long[] {-1, 0, 86460001, Long.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> RateLimitDecision.rejected(delay));
            assertThatIllegalArgumentException().isThrownBy(() -> RateLimitException.rejected(delay));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitDecision(null, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitDecision(RateLimitDecision.Outcome.ALLOWED, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitDecision(RateLimitDecision.Outcome.BYPASSED, 1));
        var rejected = RateLimitException.rejected(123);
        assertThat(rejected.reason()).isEqualTo(RateLimitException.Reason.REJECTED);
        assertThat(rejected.retryDelayMs()).isEqualTo(123);
        assertThat(rejected.getMessage()).isEqualTo("Rate limit exceeded");
        var unavailable = RateLimitException.unavailable();
        assertThat(unavailable.reason()).isEqualTo(RateLimitException.Reason.UNAVAILABLE);
        assertThat(unavailable.retryDelayMs()).isZero();
        assertThat(unavailable.getMessage()).isEqualTo("Rate limiting unavailable");
    }

    private RateLimitPolicy policy(String revision, long capacity, long refill, Duration period) {
        return new RateLimitPolicy(RateLimitDimension.IP, revision, capacity, refill, period);
    }
}
