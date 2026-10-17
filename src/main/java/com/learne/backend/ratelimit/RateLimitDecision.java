package com.learne.backend.ratelimit;

public record RateLimitDecision(Outcome outcome, long retryDelayMs) {
    public enum Outcome { ALLOWED, REJECTED, BYPASSED }

    public RateLimitDecision {
        if (outcome == null || (outcome == Outcome.REJECTED
                ? retryDelayMs <= 0 || retryDelayMs > 86460000 : retryDelayMs != 0)) {
            throw new IllegalArgumentException("Invalid rate limit decision retry delay");
        }
    }

    public static RateLimitDecision allowed() {
        return new RateLimitDecision(Outcome.ALLOWED, 0);
    }

    public static RateLimitDecision rejected(long retryDelayMs) {
        return new RateLimitDecision(Outcome.REJECTED, retryDelayMs);
    }

    public static RateLimitDecision bypassed() {
        return new RateLimitDecision(Outcome.BYPASSED, 0);
    }
}
