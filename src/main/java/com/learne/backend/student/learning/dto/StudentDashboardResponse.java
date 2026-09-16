package com.learne.backend.student.learning.dto;

import com.learne.backend.shared.dashboard.dto.LeaderboardStudentResponse;
import java.util.List;

public record StudentDashboardResponse(
        StudentStatsResponse stats,
        StudentEnrollmentSummaryResponse enrollmentSummary,
        List<StudentActiveCourseResponse> activeCourses,
        List<StudentRecentActivityResponse> recentActivity,
        List<LeaderboardStudentResponse> leaderboard
) {
}
