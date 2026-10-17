package com.learne.backend.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class RateLimitMetrics implements AutoCloseable {
    enum Outcome { ALLOWED, REJECTED, UNAVAILABLE, BYPASSED }

    private record Measurement(Counter requests, Timer duration) { }
    private final MeterRegistry registry;
    private final boolean ownedRegistry;
    private final Map<String, Map<Outcome, Measurement>> policies;
    private final Map<ClientIpResolver.FallbackReason, Counter> fallbacks;

    public RateLimitMetrics(MeterRegistry registry, Set<String> knownPolicyIds) {
        this(registry, knownPolicyIds, false);
    }

    RateLimitMetrics(MeterRegistry registry, Set<String> knownPolicyIds, boolean ownedRegistry) {
        this.registry = registry;
        this.ownedRegistry = ownedRegistry;
        var measurements = new HashMap<String, Map<Outcome, Measurement>>();
        for (String policy : Set.copyOf(knownPolicyIds)) measurements.put(policy, measurements(registry, policy));
        measurements.put("configuration", measurements(registry, "configuration"));
        this.policies = Map.copyOf(measurements);
        var reasons = new EnumMap<ClientIpResolver.FallbackReason, Counter>(ClientIpResolver.FallbackReason.class);
        for (var reason : ClientIpResolver.FallbackReason.values()) {
            reasons.put(reason, registry.counter("learne.ratelimit.client-ip.fallback", "reason", label(reason)));
        }
        this.fallbacks = Map.copyOf(reasons);
    }

    private static Map<Outcome, Measurement> measurements(MeterRegistry registry, String policy) {
        var outcomes = new EnumMap<Outcome, Measurement>(Outcome.class);
        for (var outcome : Outcome.values()) {
            String[] tags = {"policy", policy, "outcome", label(outcome)};
            outcomes.put(outcome, new Measurement(registry.counter("learne.ratelimit.requests", tags),
                    registry.timer("learne.ratelimit.duration", tags)));
        }
        return Map.copyOf(outcomes);
    }

    private static String label(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }

    void record(String policyId, Outcome outcome, long elapsedNanos) {
        var outcomes = policyId == null ? null : policies.get(policyId);
        if (outcomes == null) outcomes = policies.get("configuration");
        var measurement = outcomes.get(outcome);
        measurement.requests().increment();
        measurement.duration().record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    void clientIpFallback(ClientIpResolver.FallbackReason reason) { fallbacks.get(reason).increment(); }

    @Override
    public void close() {
        if (ownedRegistry) registry.close();
    }
}
