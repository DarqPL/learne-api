package com.learne.backend.admin.dashboard.service;

import com.learne.backend.admin.dashboard.dto.AdminDashboardResponse;
import com.learne.backend.cache.CacheNames;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class AdminDashboardReadCacheService {
    private final AdminDashboardService adminDashboardService;

    public AdminDashboardReadCacheService(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    @Cacheable(cacheNames = CacheNames.ADMIN_DASHBOARD,
            key = "T(com.learne.backend.cache.CacheKeySupport).adminDashboard()")
    public AdminDashboardResponse getDashboard() {
        return adminDashboardService.getDashboard();
    }
}
