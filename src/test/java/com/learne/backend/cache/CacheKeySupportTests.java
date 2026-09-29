package com.learne.backend.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CacheKeySupportTests {

    @Test
    void studentCatalogKeyContainsPrincipalFiltersAndCatalogVersion() {
        String key = CacheKeySupport.studentCatalog(42L, "A1", 9L, 7L);

        assertThat(key).isEqualTo("student:42:level:A1:teacher:9:catalogVersion:7");
    }

    @Test
    void studentCatalogKeyNormalizesBlankFilters() {
        String key = CacheKeySupport.studentCatalog(42L, " ", null, 7L);

        assertThat(key).isEqualTo("student:42:level:all:teacher:all:catalogVersion:7");
    }

    @Test
    void logKeyHashDoesNotExposeFullKey() {
        String key = "student:42:level:A1:teacher:9:catalogVersion:7";

        String hash = CacheKeySupport.logKeyHash(key);

        assertThat(hash).hasSize(16);
        assertThat(hash).doesNotContain("student");
        assertThat(hash).doesNotContain("42");
    }
}
