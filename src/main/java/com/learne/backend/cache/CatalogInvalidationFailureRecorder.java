package com.learne.backend.cache;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogInvalidationFailureRecorder {
    private static final int MAX_ATTEMPTS = 12;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbc;

    public CatalogInvalidationFailureRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID eventId, RuntimeException failure) {
        RuntimeException cause = failure.getCause() instanceof RuntimeException runtimeFailure
                ? runtimeFailure
                : failure;
        String message = cause.getMessage() == null
                ? cause.getClass().getSimpleName()
                : cause.getMessage();
        if (message.length() > MAX_ERROR_LENGTH) {
            message = message.substring(0, MAX_ERROR_LENGTH);
        }
        jdbc.update("""
                UPDATE catalog_invalidation_outbox
                SET attempts = attempts + 1,
                    status = CASE WHEN attempts + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                    last_error = ?, processed_at = NULL
                WHERE id = ? AND status = 'PENDING'
                """, MAX_ATTEMPTS, message, eventId);
    }
}
