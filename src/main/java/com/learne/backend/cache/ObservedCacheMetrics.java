package com.learne.backend.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ObservedCacheMetrics {
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<EvictionCounterKey, Counter> evictionCounters = new ConcurrentHashMap<>();

    public ObservedCacheMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordEviction(String cacheName, String reason) {
        evictionCounters.computeIfAbsent(new EvictionCounterKey(cacheName, reason), key -> Counter.builder("learne.cache.evictions")
                        .tag("cache", key.cacheName())
                        .tag("reason", key.reason())
                        .register(meterRegistry))
                .increment();
    }

    private record EvictionCounterKey(String cacheName, String reason) {
    }
}
