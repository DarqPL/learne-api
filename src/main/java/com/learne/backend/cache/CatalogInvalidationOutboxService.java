package com.learne.backend.cache;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogInvalidationOutboxService {
    private final JdbcTemplate jdbc;

    public CatalogInvalidationOutboxService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID recordPublicCoverChange(long courseId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog_invalidation_outbox(id, course_id)
                VALUES (?, ?)
                """, id, courseId);
        return id;
    }
}
