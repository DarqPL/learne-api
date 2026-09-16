package com.learne.backend.admin.dashboard.dto;

public record AdminDashboardOverviewResponse(
        long totalStudents,
        long lockedStudents,
        long totalTeachers,
        long lockedTeachers,
        long totalCourses,
        long totalEnrollments) {
}
