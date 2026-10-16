package com.learne.backend.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class RedisTokenBucketStore implements TokenBucketStore {
    private static final String SOURCE = loadSource();
    private static final String SHA = sha1(SOURCE);
    private final ScriptExecutor executor;

    /**
     * Transport contract: one key, four ordered immutable arguments (capacity, refillTokens,
     * periodMs, fingerprint), and a RESP integer array represented by Long items.
     * The transport owns EVALSHA/NOSCRIPT handling; the store never retries execution.
     */
    @FunctionalInterface
    public interface ScriptExecutor {
        List<?> execute(String sha, String source, String key, List<String> args);
    }

    public RedisTokenBucketStore(ScriptExecutor executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public RateLimitDecision consume(String key, RateLimitPolicy policy) {
        try {
            if (key == null || key.isEmpty() || policy == null) throw RateLimitException.unavailable();
            List<?> result = executor.execute(SHA, SOURCE, key, List.of(Long.toString(policy.capacity()),
                    Long.toString(policy.refillTokens()), Long.toString(policy.periodMs()), policy.fingerprint()));
            if (result == null || result.size() != 2
                    || !(result.get(0) instanceof Long outcome) || !(result.get(1) instanceof Long retry)) {
                throw RateLimitException.unavailable();
            }
            if (outcome == 1 && retry == 0) return RateLimitDecision.allowed();
            if (outcome == 0 && retry >= 1 && retry <= 86460000) return RateLimitDecision.rejected(retry);
            throw RateLimitException.unavailable();
        } catch (RuntimeException exception) {
            // Do not retain transport causes, keys, identifiers, or script arguments.
            throw RateLimitException.unavailable();
        }
    }

    private static String loadSource() {
        try (var stream = RedisTokenBucketStore.class.getResourceAsStream("/ratelimit/token-bucket.lua")) {
            if (stream == null) throw new IllegalStateException("Rate limit script unavailable");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Rate limit script unavailable");
        }
    }

    private static String sha1(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Rate limit script digest unavailable");
        }
    }
}
