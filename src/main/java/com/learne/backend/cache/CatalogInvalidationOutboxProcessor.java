package com.learne.backend.cache;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogInvalidationOutboxProcessor {
    private final JdbcTemplate jdbc;
    private final CatalogCacheVersionService versions;
    private final CacheInvalidationService caches;

    public CatalogInvalidationOutboxProcessor(
            JdbcTemplate jdbc,
            CatalogCacheVersionService versions,
            CacheInvalidationService caches) {
        this.jdbc = jdbc;
        this.versions = versions;
        this.caches = caches;
    }

    @Transactional
    public boolean processNext() {
        var ids = jdbc.query("""
                SELECT id
                FROM catalog_invalidation_outbox
                WHERE status = 'PENDING'
                ORDER BY created_at, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """, (rs, row) -> rs.getObject("id", UUID.class));
        if (ids.isEmpty()) {
            return false;
        }

        UUID id = ids.getFirst();
        try {
            versions.bumpVersion();
            int updated = jdbc.update("""
                    UPDATE catalog_invalidation_outbox
                    SET status = 'PROCESSED', attempts = attempts + 1,
                        last_error = NULL, processed_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'PENDING'
                    """, id);
            if (updated != 1) {
                throw new IllegalStateException("Catalog invalidation event is no longer pending.");
            }
            caches.evictGlobalCourseCatalogCaches();
            return true;
        } catch (RuntimeException failure) {
            throw new CatalogInvalidationProcessingException(id, failure);
        }
    }

    public static final class CatalogInvalidationProcessingException extends RuntimeException {
        private final UUID eventId;

        public CatalogInvalidationProcessingException(UUID eventId, RuntimeException cause) {
            super("Catalog invalidation event processing failed.", cause);
            this.eventId = eventId;
        }

        public UUID eventId() {
            return eventId;
        }
    }
}
