package com.learne.backend.cache;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CatalogCacheVersionService {
    private final AtomicLong version = new AtomicLong(1L);
    private final JdbcTemplate jdbc;

    public CatalogCacheVersionService() {
        this.jdbc = null;
    }

    @Autowired
    public CatalogCacheVersionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long currentVersion() {
        return jdbc == null ? version.get()
                : jdbc.queryForObject("SELECT version FROM catalog_cache_state WHERE id = 1", Long.class);
    }

    public long bumpVersion() {
        if (jdbc == null) {
            return version.incrementAndGet();
        }
        if (jdbc.update("UPDATE catalog_cache_state SET version = version + 1 WHERE id = 1") != 1) {
            throw new IllegalStateException("Catalog cache version state is missing.");
        }
        return currentVersion();
    }
}
