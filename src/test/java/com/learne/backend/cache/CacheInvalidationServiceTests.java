package com.learne.backend.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.cache.redis.enabled=false")
class CacheInvalidationServiceTests {

    @Autowired CacheManager cacheManager;
    @Autowired CacheInvalidationService invalidationService;
    @Autowired CatalogCacheVersionService catalogCacheVersionService;
    @Autowired MeterRegistry meterRegistry;

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

    @Test
    void recordsEvictionMetricsWithBoundedTags() {
        String cacheName = CacheNames.TEACHER_DASHBOARD;
        String key = CacheKeySupport.teacherDashboard(10L);
        cacheManager.getCache(cacheName).put(key, "dashboard");
        double before = evictionCount(cacheName, "keyed");

        invalidationService.evictTeacherDashboard(10L);

        assertThat(evictionCount(cacheName, "keyed")).isEqualTo(before + 1.0d);
        assertThat(meterRegistry.find("learne.cache.evictions")
                .tag("cache", cacheName)
                .tag("reason", "10")
                .counter()).isNull();
    }

    @Test
    void defersEvictionUntilTransactionCommit() {
        Cache dashboard = cacheManager.getCache(CacheNames.STUDENT_DASHBOARD);
        String dashboardKey = CacheKeySupport.studentDashboard(20L);
        dashboard.put(dashboardKey, "dashboard");

        try {
            TransactionSynchronizationManager.initSynchronization();

            invalidationService.evictStudentLearning(20L);

            assertThat(dashboard.get(dashboardKey)).isNotNull();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(dashboard.get(dashboardKey)).isNull();
        } finally {
            clearSynchronization();
        }
    }

    @Test
    void keepsCachesWhenTransactionRollsBackBeforeCommit() {
        Cache dashboard = cacheManager.getCache(CacheNames.STUDENT_DASHBOARD);
        String dashboardKey = CacheKeySupport.studentDashboard(30L);
        dashboard.put(dashboardKey, "dashboard");

        try {
            TransactionSynchronizationManager.initSynchronization();

            invalidationService.evictStudentLearning(30L);

            assertThat(dashboard.get(dashboardKey)).isNotNull();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(dashboard.get(dashboardKey)).isNotNull();
        } finally {
            clearSynchronization();
        }
    }

    @Test
    void keyedEvictionFailurePropagatesImmediately() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheInvalidationService service = new CacheInvalidationService(
                new SingleCacheManager(CacheNames.TEACHER_DASHBOARD, new ThrowingEvictIfPresentCache(CacheNames.TEACHER_DASHBOARD)),
                new CatalogCacheVersionService(),
                new ObservedCacheMetrics(registry));

        assertThatThrownBy(() -> service.evictTeacherDashboard(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("evict failed");
        assertThat(evictionCount(registry, CacheNames.TEACHER_DASHBOARD, "keyed")).isZero();
    }

    @Test
    void doesNotRecordMetricWhenKeyedEvictionDoesNotMutateCache() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheInvalidationService service = new CacheInvalidationService(
                new SingleCacheManager(CacheNames.TEACHER_DASHBOARD, new MissingKeyCache(CacheNames.TEACHER_DASHBOARD)),
                new CatalogCacheVersionService(),
                new ObservedCacheMetrics(registry));

        service.evictTeacherDashboard(10L);

        assertThat(evictionCount(registry, CacheNames.TEACHER_DASHBOARD, "keyed")).isZero();
    }

    @Test
    void doesNotRecordMetricWhenFullInvalidationDoesNotMutateCache() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheInvalidationService service = new CacheInvalidationService(
                new SingleCacheManager(CacheNames.LEADERBOARD, new EmptyCache(CacheNames.LEADERBOARD)),
                new CatalogCacheVersionService(),
                new ObservedCacheMetrics(registry));

        service.evictLeaderboard();

        assertThat(evictionCount(registry, CacheNames.LEADERBOARD, "full")).isZero();
    }

    @Test
    void catalogVersionDoesNotAdvanceWhenCatalogClearFails() {
        CatalogCacheVersionService versionService = new CatalogCacheVersionService();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheInvalidationService service = new CacheInvalidationService(
                new SingleCacheManager(CacheNames.STUDENT_CATALOG, new ThrowingInvalidateCache(CacheNames.STUDENT_CATALOG)),
                versionService,
                new ObservedCacheMetrics(registry));
        long before = versionService.currentVersion();

        assertThatThrownBy(service::evictGlobalCourseCatalog)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalidate failed");
        assertThat(versionService.currentVersion()).isEqualTo(before);
        assertThat(evictionCount(registry, CacheNames.STUDENT_CATALOG, "full")).isZero();
    }

    private double evictionCount(String cacheName, String reason) {
        return evictionCount(meterRegistry, cacheName, reason);
    }

    private static double evictionCount(MeterRegistry registry, String cacheName, String reason) {
        Counter counter = registry.find("learne.cache.evictions")
                .tag("cache", cacheName)
                .tag("reason", reason)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    private static void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static final class SingleCacheManager implements CacheManager {
        private final Map<String, Cache> caches = new ConcurrentHashMap<>();

        private SingleCacheManager(String cacheName, Cache cache) {
            caches.put(cacheName, cache);
        }

        @Override
        public Cache getCache(String name) {
            return caches.get(name);
        }

        @Override
        public Collection<String> getCacheNames() {
            return caches.keySet();
        }
    }

    private static final class ThrowingEvictIfPresentCache extends ConcurrentMapCache {
        private ThrowingEvictIfPresentCache(String name) {
            super(name);
        }

        @Override
        public boolean evictIfPresent(Object key) {
            throw new IllegalStateException("evict failed");
        }
    }

    private static final class MissingKeyCache extends ConcurrentMapCache {
        private MissingKeyCache(String name) {
            super(name);
        }

        @Override
        public boolean evictIfPresent(Object key) {
            return false;
        }
    }

    private static final class EmptyCache extends ConcurrentMapCache {
        private EmptyCache(String name) {
            super(name);
        }

        @Override
        public boolean invalidate() {
            return false;
        }
    }

    private static final class ThrowingInvalidateCache extends ConcurrentMapCache {
        private ThrowingInvalidateCache(String name) {
            super(name);
        }

        @Override
        public boolean invalidate() {
            throw new IllegalStateException("invalidate failed");
        }
    }
}
