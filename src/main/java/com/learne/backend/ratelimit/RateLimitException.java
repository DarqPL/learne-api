package com.learne.backend.ratelimit;

public final class RateLimitException extends RuntimeException {
    public enum Reason { REJECTED, UNAVAILABLE }

    private final Reason reason;
    private final long retryDelayMs;

    private RateLimitException(Reason reason, long retryDelayMs) {
        super(reason == Reason.REJECTED ? "Rate limit exceeded" : "Rate limiting unavailable");
        this.reason = reason;
        this.retryDelayMs = retryDelayMs;
    }

    public static RateLimitException rejected(long retryDelayMs) {
        return new RateLimitException(Reason.REJECTED, RateLimitDecision.rejected(retryDelayMs).retryDelayMs());
    }

    public static RateLimitException unavailable() {
        return new RateLimitException(Reason.UNAVAILABLE, 0);
    }

    public Reason reason() {
        return reason;
    }

    public long retryDelayMs() {
        return retryDelayMs;
    }
}
