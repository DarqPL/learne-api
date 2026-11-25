package com.learne.backend.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class CatalogCacheVersionPersistenceTests {
    @Autowired
    private CatalogCacheVersionService versions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void catalogVersionBumpPersistsForOtherServiceInstances() {
        long before = jdbc.queryForObject(
                "select version from catalog_cache_state where id = 1", Long.class);

        assertThat(versions.bumpVersion()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject(
                "select version from catalog_cache_state where id = 1", Long.class))
                .isEqualTo(before + 1);
    }
}
