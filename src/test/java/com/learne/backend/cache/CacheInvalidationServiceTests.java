package com.learne.backend.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.cache.redis.enabled=false")
class CacheInvalidationServiceTests {

    @Autowired CacheManager cacheManager;
    @Autowired CacheInvalidationService invalidationService;
    @Autowired CatalogCacheVersionService catalogCacheVersionService;

    @Test
    void evictsStudentLearningCaches() {
        Cache catalog = cacheManager.getCache(CacheNames.STUDENT_CATALOG);
        Cache dashboard = cacheManager.getCache(CacheNames.STUDENT_DASHBOARD);
        String catalogKey = CacheKeySupport.studentCatalog(10L, "A1", null, catalogCacheVersionService.currentVersion());
        String dashboardKey = CacheKeySupport.studentDashboard(10L);
        catalog.put(catalogKey, "catalog");
        dashboard.put(dashboardKey, "dashboard");

        invalidationService.evictStudentLearning(10L);

        assertThat(catalog.get(catalogKey)).isNull();
        assertThat(dashboard.get(dashboardKey)).isNull();
    }

    @Test
    void courseCatalogInvalidationBumpsVersion() {
        long before = catalogCacheVersionService.currentVersion();

        invalidationService.evictGlobalCourseCatalog();

        assertThat(catalogCacheVersionService.currentVersion()).isGreaterThan(before);
    }
}
