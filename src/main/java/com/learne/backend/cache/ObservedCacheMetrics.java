package com.learne.backend.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ObservedCacheMetrics {
    private final MeterRegistry meterRegistry;

    public ObservedCacheMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordEviction(String cacheName, String reason) {
        Counter.builder("learne.cache.evictions")
                .tag("cache", cacheName)
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }
}
