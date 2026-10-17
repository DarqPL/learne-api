package com.learne.backend.ratelimit;

import java.util.function.Supplier;

public final class RateLimitService {
    private final RateLimitProperties properties;
    private final RateLimitKeyFactory keyFactory;
    private final Supplier<TokenBucketStore> store;
    private final RateLimitMetrics metrics;

    public RateLimitService(RateLimitProperties properties, RateLimitKeyFactory keyFactory,
            Supplier<TokenBucketStore> store, RateLimitMetrics metrics) {
        if (properties == null || metrics == null) throw RateLimitException.unavailable();
        this.properties = properties;
        this.keyFactory = keyFactory;
        this.store = store;
        this.metrics = metrics;
    }

    public RateLimitDecision consume(String policyId, RateLimitDimension dimension, String identifier) {
        long started = System.nanoTime();
        var outcome = RateLimitMetrics.Outcome.UNAVAILABLE;
        try {
            // Bypass precedes policy lookup, key derivation and provider access, including invalid inputs.
            if (!properties.enabled()) {
                outcome = RateLimitMetrics.Outcome.BYPASSED;
                return RateLimitDecision.bypassed();
            }
            var policy = policyId == null ? null : properties.policies().get(policyId);
            if (policy == null) throw RateLimitException.unavailable();
            String key = keyFactory.key(policyId, policy, dimension, identifier);
            var decision = store.get().consume(key, policy);
            if (decision == null || decision.outcome() == RateLimitDecision.Outcome.BYPASSED) {
                throw RateLimitException.unavailable();
            }
            outcome = decision.outcome() == RateLimitDecision.Outcome.ALLOWED
                    ? RateLimitMetrics.Outcome.ALLOWED : RateLimitMetrics.Outcome.REJECTED;
            return decision;
        } catch (RuntimeException failure) {
            // Neither caller input nor dependency causes cross the service boundary.
            throw RateLimitException.unavailable();
        } finally {
            metrics.record(policyId, outcome, System.nanoTime() - started);
        }
    }

    public void requireAllowed(String policyId, RateLimitDimension dimension, String identifier) {
        var decision = consume(policyId, dimension, identifier);
        if (decision.outcome() == RateLimitDecision.Outcome.REJECTED) {
            throw RateLimitException.rejected(decision.retryDelayMs());
        }
    }
}
