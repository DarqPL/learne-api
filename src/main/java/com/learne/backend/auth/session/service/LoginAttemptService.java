package com.learne.backend.auth.session.service;

import com.learne.backend.shared.api.ApiException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration COOLDOWN = Duration.ofMinutes(15);
    private static final String TOO_MANY_ATTEMPTS_MESSAGE = "Too many failed login attempts. Please try again later.";

    private final Map<String, AttemptState> attemptsByEmail = new ConcurrentHashMap<>();

    public void assertCanAttempt(String email) {
        String normalizedEmail = normalizeEmail(email);
        AttemptState attemptState = attemptsByEmail.get(normalizedEmail);
        if (attemptState == null || attemptState.lockedUntil() == null) {
            return;
        }
        if (attemptState.lockedUntil().isAfter(OffsetDateTime.now())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_ATTEMPTS_MESSAGE);
        }
        attemptsByEmail.remove(normalizedEmail);
    }

    public void recordFailure(String email) {
        String normalizedEmail = normalizeEmail(email);
        attemptsByEmail.compute(normalizedEmail, (key, current) -> {
            int failedAttempts = current == null ? 1 : current.failedAttempts() + 1;
            OffsetDateTime lockedUntil = failedAttempts >= MAX_FAILED_ATTEMPTS
                    ? OffsetDateTime.now().plus(COOLDOWN)
                    : null;
            return new AttemptState(failedAttempts, lockedUntil);
        });
    }

    public void clear(String email) {
        attemptsByEmail.remove(normalizeEmail(email));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private record AttemptState(int failedAttempts, OffsetDateTime lockedUntil) {
    }
}
