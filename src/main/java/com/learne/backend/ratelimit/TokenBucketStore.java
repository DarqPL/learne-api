package com.learne.backend.ratelimit;

public interface TokenBucketStore {
    RateLimitDecision consume(String key, RateLimitPolicy policy);
}
