package com.learne.backend.admin.dashboard.dto;

import com.learne.backend.shared.dashboard.dto.LeaderboardStudentResponse;
import com.learne.backend.shared.dashboard.dto.StatusCountResponse;
import java.util.List;

public record AdminDashboardResponse(
        AdminDashboardOverviewResponse overview,
        List<StatusCountResponse> courseStatusCounts,
        List<LeaderboardStudentResponse> leaderboard) {
}
