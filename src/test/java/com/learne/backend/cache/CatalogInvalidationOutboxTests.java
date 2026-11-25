package com.learne.backend.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.cache.invalidation-outbox.fixed-delay=3600000")
class CatalogInvalidationOutboxTests {
    @Autowired
    private CatalogInvalidationOutboxWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private CatalogCacheVersionService versions;

    @Test
    void failedInvalidationRemainsRetryableAndSuccessfulRetryBumpsVersion() {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog_invalidation_outbox(id, course_id)
                VALUES (?, 42)
                """, eventId);
        long before = versions.currentVersion();
        doThrow(new IllegalStateException("temporary version-store failure"))
                .doCallRealMethod()
                .when(versions).bumpVersion();

        worker.drain();

        var failed = jdbc.queryForMap(
                "SELECT status, attempts, last_error FROM catalog_invalidation_outbox WHERE id = ?", eventId);
        assertThat(failed).containsEntry("status", "PENDING")
                .containsEntry("attempts", 1)
                .containsEntry("last_error", "temporary version-store failure");

        worker.drain();

        var completed = jdbc.queryForMap(
                "SELECT status, attempts, processed_at FROM catalog_invalidation_outbox WHERE id = ?", eventId);
        assertThat(completed).containsEntry("status", "PROCESSED")
                .containsEntry("attempts", 2);
        assertThat(completed.get("processed_at")).isNotNull();
        assertThat(versions.currentVersion()).isEqualTo(before + 1);
    }
}
