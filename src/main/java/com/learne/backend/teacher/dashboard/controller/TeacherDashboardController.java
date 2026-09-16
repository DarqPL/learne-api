package com.learne.backend.teacher.dashboard.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.dashboard.dto.TeacherDashboardResponse;
import com.learne.backend.teacher.dashboard.service.TeacherDashboardService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher/dashboard")
class TeacherDashboardController {
    private final TeacherDashboardService teacherDashboardService;

    TeacherDashboardController(TeacherDashboardService teacherDashboardService) {
        this.teacherDashboardService = teacherDashboardService;
    }

    @GetMapping
    TeacherDashboardResponse getDashboard(Authentication authentication) {
        return teacherDashboardService.getDashboard((User) authentication.getPrincipal());
    }
}
