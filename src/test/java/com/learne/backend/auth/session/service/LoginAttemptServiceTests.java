package com.learne.backend.auth.session.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.shared.api.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class LoginAttemptServiceTests {

    @Test
    void recordFailureRejectsAttemptsAfterTheFifthFailureAtomically() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-18T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(clock);

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatCode(() -> service.recordFailure("Student@Example.com")).doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> service.assertCanAttempt("student@example.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException apiException = (ApiException) error;
                    org.assertj.core.api.Assertions.assertThat(apiException.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    org.assertj.core.api.Assertions.assertThat(apiException.getMessage()).isEqualTo("Too many failed login attempts. Please try again later.");
                });

        assertThatThrownBy(() -> service.recordFailure("student@example.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException apiException = (ApiException) error;
                    org.assertj.core.api.Assertions.assertThat(apiException.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    org.assertj.core.api.Assertions.assertThat(apiException.getMessage()).isEqualTo("Too many failed login attempts. Please try again later.");
                });
    }

    @Test
    void failedAttemptsBelowThresholdExpireFromMemory() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-18T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(clock);

        for (int attempt = 0; attempt < 4; attempt++) {
            service.recordFailure("student@example.com");
        }

        clock.advance(Duration.ofMinutes(16));

        for (int index = 0; index < 10_000; index++) {
            service.recordFailure("stale-cleanup-" + index + "@example.com");
        }

        for (int attempt = 0; attempt < 4; attempt++) {
            service.recordFailure("student@example.com");
        }

        assertThatCode(() -> service.assertCanAttempt("student@example.com")).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
