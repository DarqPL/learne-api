package com.learne.backend.admin.dashboard.controller;

import com.learne.backend.admin.dashboard.dto.AdminDashboardResponse;
import com.learne.backend.admin.dashboard.service.AdminDashboardReadCacheService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
class AdminDashboardController {

    private final AdminDashboardReadCacheService adminDashboardReadCacheService;

    AdminDashboardController(AdminDashboardReadCacheService adminDashboardReadCacheService) {
        this.adminDashboardReadCacheService = adminDashboardReadCacheService;
    }

    @GetMapping
    AdminDashboardResponse getDashboard() {
        return adminDashboardReadCacheService.getDashboard();
    }
}
