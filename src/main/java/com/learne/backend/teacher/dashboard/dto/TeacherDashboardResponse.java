package com.learne.backend.teacher.dashboard.dto;

import com.learne.backend.shared.dashboard.dto.StatusCountResponse;
import java.util.List;

public record TeacherDashboardResponse(
        TeacherDashboardOverviewResponse overview,
        List<StatusCountResponse> courseStatusCounts,
        List<TeacherRecentCourseResponse> recentCourses
) {
}
