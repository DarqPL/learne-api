package com.learne.backend.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.cache.invalidation-outbox",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CatalogInvalidationOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(CatalogInvalidationOutboxWorker.class);
    private static final int BATCH_SIZE = 100;

    private final CatalogInvalidationOutboxProcessor processor;
    private final CatalogInvalidationFailureRecorder failures;

    public CatalogInvalidationOutboxWorker(
            CatalogInvalidationOutboxProcessor processor,
            CatalogInvalidationFailureRecorder failures) {
        this.processor = processor;
        this.failures = failures;
    }

    @Scheduled(fixedDelayString = "${app.cache.invalidation-outbox.fixed-delay:5000}")
    public void drain() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            try {
                if (!processor.processNext()) {
                    return;
                }
            } catch (CatalogInvalidationOutboxProcessor.CatalogInvalidationProcessingException failure) {
                failures.record(failure.eventId(), failure);
                log.error("Catalog invalidation event failed: eventId={}", failure.eventId(), failure);
                return;
            }
        }
    }
}
