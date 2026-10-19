package com.learne.backend.teacher.content.enrichment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Independent of interactive AI timeouts: allows the Python 110-second total budget. */
@ConfigurationProperties("app.course-enrichment")
public record EnrichmentClientProperties(Integer timeoutMs, Integer connectTimeoutMs, Integer maxInFlight) {
    public EnrichmentClientProperties {
        timeoutMs = timeoutMs == null ? 120_000 : timeoutMs;
        connectTimeoutMs = connectTimeoutMs == null ? 5_000 : connectTimeoutMs;
        maxInFlight = maxInFlight == null ? 2 : maxInFlight;
        if (timeoutMs < 1 || timeoutMs > 120_000 || connectTimeoutMs < 1 || connectTimeoutMs > 5_000
                || maxInFlight < 1 || maxInFlight > 8) {
            throw new IllegalArgumentException("Invalid enrichment HTTP configuration");
        }
    }
}
