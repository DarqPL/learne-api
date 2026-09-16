package com.learne.backend.teacher.dashboard.dto;

public record TeacherDashboardOverviewResponse(
        long totalCourses,
        long enrolledStudents,
        long totalLessons,
        long totalQuestions
) {
}
