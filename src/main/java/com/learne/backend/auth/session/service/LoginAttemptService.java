package com.learne.backend.auth.session.service;

import com.learne.backend.shared.api.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int MAX_TRACKED_EMAILS = 10_000;
    private static final Duration COOLDOWN = Duration.ofMinutes(15);
    private static final Duration FAILED_ATTEMPT_TTL = Duration.ofMinutes(15);
    private static final String TOO_MANY_ATTEMPTS_MESSAGE = "Too many failed login attempts. Please try again later.";

    private final Map<String, AttemptState> attemptsByEmail = new ConcurrentHashMap<>();
    private final Object[] locks = new Object[64];
    private final Clock clock;

    public LoginAttemptService() {
        this(Clock.systemUTC());
    }

    LoginAttemptService(Clock clock) {
        this.clock = clock;
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
    }

    public <T> T withEmailLock(String email, Supplier<T> action) {
        synchronized (lockFor(email)) {
            return action.get();
        }
    }

    public void assertCanAttempt(String email) {
        String normalizedEmail = normalizeEmail(email);
        AttemptState attemptState = attemptsByEmail.get(normalizedEmail);
        if (attemptState == null) {
            return;
        }
        if (attemptState.lockedUntil() != null && attemptState.lockedUntil().isAfter(now())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_ATTEMPTS_MESSAGE);
        }
        if (isExpired(attemptState)) {
            attemptsByEmail.remove(normalizedEmail, attemptState);
        }
    }

    public void recordFailure(String email) {
        String normalizedEmail = normalizeEmail(email);
        attemptsByEmail.compute(normalizedEmail, (key, current) -> {
            if (current != null && isExpired(current)) {
                current = null;
            }
            if (current != null && current.lockedUntil() != null && current.lockedUntil().isAfter(now())) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_ATTEMPTS_MESSAGE);
            }
            int failedAttempts = current == null ? 1 : current.failedAttempts() + 1;
            Instant lockedUntil = failedAttempts >= MAX_FAILED_ATTEMPTS
                    ? now().plus(COOLDOWN)
                    : null;
            if (failedAttempts > MAX_FAILED_ATTEMPTS) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_ATTEMPTS_MESSAGE);
            }
            return new AttemptState(failedAttempts, lockedUntil, now().plus(FAILED_ATTEMPT_TTL));
        });
        cleanupIfNeeded(normalizedEmail);
    }

    public void clear(String email) {
        attemptsByEmail.remove(normalizeEmail(email));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private Object lockFor(String email) {
        return locks[Math.floorMod(normalizeEmail(email).hashCode(), locks.length)];
    }

    private void cleanupIfNeeded(String protectedEmail) {
        if (attemptsByEmail.size() >= MAX_TRACKED_EMAILS) {
            attemptsByEmail.entrySet().removeIf(entry -> isExpired(entry.getValue()));
        }
        if (attemptsByEmail.size() >= MAX_TRACKED_EMAILS) {
            attemptsByEmail.keySet().stream()
                    .filter(email -> !email.equals(protectedEmail))
                    .filter(email -> !isActiveLock(attemptsByEmail.get(email)))
                    .limit(attemptsByEmail.size() - MAX_TRACKED_EMAILS + 1L)
                    .toList()
                    .forEach(attemptsByEmail::remove);
        }
    }

    private boolean isExpired(AttemptState attemptState) {
        if (attemptState.lockedUntil() != null) {
            return !attemptState.lockedUntil().isAfter(now());
        }
        return !attemptState.expiresAt().isAfter(now());
    }

    private boolean isActiveLock(AttemptState attemptState) {
        return attemptState != null
                && attemptState.lockedUntil() != null
                && attemptState.lockedUntil().isAfter(now());
    }

    private Instant now() {
        return clock.instant();
    }

    private record AttemptState(int failedAttempts, Instant lockedUntil, Instant expiresAt) {
    }
}
