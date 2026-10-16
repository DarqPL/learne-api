package com.learne.backend.auth.session.service;

import com.learne.backend.ratelimit.RateLimitDimension;
import com.learne.backend.ratelimit.RateLimitException;
import com.learne.backend.ratelimit.RateLimitKeyFactory;
import com.learne.backend.ratelimit.RateLimitProperties;
import com.learne.backend.ratelimit.RedisTokenBucketStore.ScriptExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class RedisLoginAttemptStore {
    private static final String SOURCE = loadSource();
    private static final String SHA = sha1(SOURCE);
    private final RateLimitProperties properties;
    private final RateLimitKeyFactory keyFactory;
    private final ScriptExecutor executor;

    public RedisLoginAttemptStore(RateLimitProperties properties, RateLimitKeyFactory keyFactory, ScriptExecutor executor) {
        this.properties = Objects.requireNonNull(properties);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.executor = Objects.requireNonNull(executor);
    }

    public void assertCanAttempt(String email) {
        execute("check", email);
    }

    public void recordFailure(String email) {
        execute("failure", email);
    }

    public void clear(String email) {
        execute("clear", email);
    }

    private void execute(String operation, String email) {
        long retry;
        try {
            if (email == null || email.indexOf('\0') >= 0) throw RateLimitException.unavailable();
            String canonical = email.trim().toLowerCase(Locale.ROOT);
            if (canonical.isBlank()) throw RateLimitException.unavailable();
            String key = properties.namespace() + ":" + properties.environment() + ":auth:login-failures:v1:email:"
                    + keyFactory.hmacCanonicalIdentifier(RateLimitDimension.EMAIL, canonical);
            // The shared Phase 1 transport owns NOSCRIPT recovery. Never retry a logical operation here.
            List<?> result = executor.execute(SHA, SOURCE, key, List.of(operation, "5", "900000"));
            if (result == null || result.size() != 2 || !(result.get(0) instanceof Long outcome)
                    || !(result.get(1) instanceof Long delay)) throw RateLimitException.unavailable();
            if (outcome == 1 && delay == 0) return;
            if (outcome != 0 || delay < 1 || delay > 86460000) throw RateLimitException.unavailable();
            retry = delay;
        } catch (RuntimeException exception) {
            // No transport causes, raw identifiers, keys, or arguments escape this boundary.
            throw RateLimitException.unavailable();
        }
        // Keep our validated quota rejection outside the dependency-error boundary.
        throw RateLimitException.rejected(retry);
    }

    private static String loadSource() {
        try (var stream = RedisLoginAttemptStore.class.getResourceAsStream("/auth/login-attempts.lua")) {
            if (stream == null) throw new IllegalStateException("Login attempt script unavailable");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Login attempt script unavailable");
        }
    }

    private static String sha1(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Login attempt script digest unavailable");
        }
    }
}
