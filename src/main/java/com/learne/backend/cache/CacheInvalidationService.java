package com.learne.backend.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CacheInvalidationService {
    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationService.class);

    private final CacheManager cacheManager;
    private final CatalogCacheVersionService catalogCacheVersionService;

    public CacheInvalidationService(CacheManager cacheManager, CatalogCacheVersionService catalogCacheVersionService) {
        this.cacheManager = cacheManager;
        this.catalogCacheVersionService = catalogCacheVersionService;
    }

    public void evictStudentLearning(Long studentId) {
        String dashboardKey = CacheKeySupport.studentDashboard(studentId);
        String accessKey = CacheKeySupport.courseAccess(studentId);

        afterCommit(() -> {
            evict(CacheNames.STUDENT_DASHBOARD, dashboardKey);
            clear(CacheNames.STUDENT_CATALOG);
            clear(CacheNames.STUDENT_COURSE_DETAIL);
            evict(CacheNames.COURSE_ACCESS, accessKey);
        });
    }

    public void evictAccessForStudent(Long studentId) {
        String accessKey = CacheKeySupport.courseAccess(studentId);

        afterCommit(() -> {
            evict(CacheNames.COURSE_ACCESS, accessKey);
            clear(CacheNames.STUDENT_CATALOG);
            clear(CacheNames.STUDENT_COURSE_DETAIL);
        });
    }

    public void evictTeacherDashboard(Long teacherId) {
        String dashboardKey = CacheKeySupport.teacherDashboard(teacherId);

        afterCommit(() -> evict(CacheNames.TEACHER_DASHBOARD, dashboardKey));
    }

    public void evictAdminDashboard() {
        afterCommit(() -> evict(CacheNames.ADMIN_DASHBOARD, CacheKeySupport.adminDashboard()));
    }

    public void evictLeaderboard() {
        afterCommit(() -> clear(CacheNames.LEADERBOARD));
    }

    public void evictGlobalCourseCatalog() {
        afterCommit(() -> {
            long nextVersion = catalogCacheVersionService.bumpVersion();
            clear(CacheNames.STUDENT_CATALOG);
            clear(CacheNames.STUDENT_COURSE_DETAIL);
            log.info("Bumped course catalog cache version to {}", nextVersion);
        });
    }

    private void evict(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }

    private void clear(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
