package com.learne.backend.shared.dashboard.dto;

import java.time.LocalDate;

public record LeaderboardStudentResponse(
        Long userId,
        String fullName,
        int totalXp,
        int currentStreakDays,
        int longestStreakDays,
        LocalDate lastActivityDate
) {
}
