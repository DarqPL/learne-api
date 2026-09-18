package com.learne.backend.auth.session.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.shared.api.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test
    void expiredFailedAttemptsBelowThresholdResetBeforeNextFailure() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-18T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(clock);

        for (int attempt = 0; attempt < 4; attempt++) {
            service.recordFailure("student@example.com");
        }

        clock.advance(Duration.ofMinutes(16));

        service.recordFailure("student@example.com");

        assertThatCode(() -> service.assertCanAttempt("student@example.com")).doesNotThrowAnyException();
    }

    @Test
    void cleanupDoesNotRemoveActiveLockedEntriesWhenSizeCapIsReached() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-18T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(clock);

        for (int index = 0; index < 10_000; index++) {
            lockAccount(service, "locked-" + index + "@example.com");
        }

        service.recordFailure("new-student@example.com");

        for (int index = 0; index < 10_000; index++) {
            String lockedEmail = "locked-" + index + "@example.com";
            assertThatThrownBy(() -> service.assertCanAttempt(lockedEmail))
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> {
                        ApiException apiException = (ApiException) error;
                        org.assertj.core.api.Assertions.assertThat(apiException.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    });
        }
    }

    @Test
    void cleanupDoesNotRemoveEntryThatBecomesLockedAfterCleanupCheck() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-18T00:00:00Z"));
        String racyEmail = "racy-student@example.com";
        RacyCleanupMap attemptsByEmail = new RacyCleanupMap(racyEmail);
        LoginAttemptService service = new LoginAttemptService(clock, attemptsByEmail);
        attemptsByEmail.setService(service);

        for (int index = 0; index < 9_998; index++) {
            lockAccount(service, "locked-" + index + "@example.com");
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            service.recordFailure(racyEmail);
        }

        service.recordFailure("new-student@example.com");

        assertThatThrownBy(() -> service.assertCanAttempt(racyEmail))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException apiException = (ApiException) error;
                    org.assertj.core.api.Assertions.assertThat(apiException.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                });
    }

    private static void lockAccount(LoginAttemptService service, String email) {
        for (int attempt = 0; attempt < 5; attempt++) {
            service.recordFailure(email);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class RacyCleanupMap extends ConcurrentHashMap {
        private final String racyEmail;
        private final AtomicReference<LoginAttemptService> service = new AtomicReference<>();
        private boolean transitionedToActiveLock;

        private RacyCleanupMap(String racyEmail) {
            this.racyEmail = racyEmail;
        }

        private void setService(LoginAttemptService service) {
            this.service.set(service);
        }

        @Override
        public Object remove(Object key) {
            transitionToActiveLockIfRacy(key);
            return super.remove(key);
        }

        @Override
        public boolean remove(Object key, Object value) {
            transitionToActiveLockIfRacy(key);
            return super.remove(key, value);
        }

        private void transitionToActiveLockIfRacy(Object key) {
            if (!transitionedToActiveLock && racyEmail.equals(key)) {
                transitionedToActiveLock = true;
                service.get().recordFailure(racyEmail);
            }
        }
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
