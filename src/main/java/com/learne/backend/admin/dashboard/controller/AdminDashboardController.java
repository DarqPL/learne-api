package com.learne.backend.admin.dashboard.controller;

import com.learne.backend.admin.dashboard.dto.AdminDashboardResponse;
import com.learne.backend.admin.dashboard.service.AdminDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    AdminDashboardController(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    @GetMapping
    AdminDashboardResponse getDashboard() {
        return adminDashboardService.getDashboard();
    }
}
