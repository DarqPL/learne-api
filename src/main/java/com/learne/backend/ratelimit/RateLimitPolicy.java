package com.learne.backend.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

public record RateLimitPolicy(RateLimitDimension dimension, String revision, long capacity,
        long refillTokens, Duration refillPeriod) {

    public RateLimitPolicy {
        if (dimension == null || revision == null || !revision.matches("r[1-9][0-9]{0,8}")) {
            throw new IllegalArgumentException("Rate limit dimension and revision must be valid");
        }
        if (capacity < 1 || capacity > 1000000 || refillTokens < 1 || refillTokens > 1000000) {
            throw new IllegalArgumentException("Rate limit token counts must be between 1 and 1000000");
        }
        // Compare before converting so even extreme Duration values cannot overflow.
        if (refillPeriod == null || refillPeriod.compareTo(Duration.ofSeconds(1)) < 0
                || refillPeriod.compareTo(Duration.ofDays(1)) > 0 || refillPeriod.getNano() % 1000000 != 0) {
            throw new IllegalArgumentException("Rate limit period must be exact milliseconds between 1000 and 86400000");
        }
        long credit = capacity * refillPeriod.toMillis();
        long fullRefillMs = (credit + refillTokens - 1) / refillTokens;
        if (fullRefillMs > 86400000) {
            throw new IllegalArgumentException("Rate limit full refill must not exceed 86400000 milliseconds");
        }
    }

    public long periodMs() {
        return refillPeriod.toMillis();
    }

    public long capacityCredit() {
        return capacity * periodMs();
    }

    public String fingerprint() {
        String canonical = "tb1|" + dimension.wire() + "|" + capacity + "|" + refillTokens + "|" + periodMs();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
