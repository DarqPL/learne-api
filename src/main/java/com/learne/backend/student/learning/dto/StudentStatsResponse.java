package com.learne.backend.student.learning.dto;

import com.learne.backend.student.learning.entity.UserStats;
import java.time.LocalDate;

public record StudentStatsResponse(
        int totalXp,
        int currentStreakDays,
        int longestStreakDays,
        LocalDate lastActivityDate
) {
    public static StudentStatsResponse from(UserStats stats) {
        return new StudentStatsResponse(
                stats.getTotalXp(),
                stats.getCurrentStreakDays(),
                stats.getLongestStreakDays(),
                stats.getLastActivityDate()
        );
    }
}
