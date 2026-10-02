package com.learne.backend.teacher.dashboard.service;

import com.learne.backend.cache.CacheNames;
import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.dashboard.dto.TeacherDashboardResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class TeacherDashboardReadCacheService {
    private final TeacherDashboardService teacherDashboardService;

    public TeacherDashboardReadCacheService(TeacherDashboardService teacherDashboardService) {
        this.teacherDashboardService = teacherDashboardService;
    }

    @Cacheable(cacheNames = CacheNames.TEACHER_DASHBOARD,
            key = "T(com.learne.backend.cache.CacheKeySupport).teacherDashboard(#teacher.id)")
    public TeacherDashboardResponse getDashboard(User teacher) {
        return teacherDashboardService.getDashboard(teacher);
    }
}
