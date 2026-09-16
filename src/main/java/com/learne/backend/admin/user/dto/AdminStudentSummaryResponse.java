package com.learne.backend.admin.user.dto;

import java.time.LocalDate;

public record AdminStudentSummaryResponse(
        Long userId,
        String fullName,
        String email,
        boolean locked,
        boolean mustChangePassword,
        int totalXp,
        int currentStreakDays,
        int longestStreakDays,
        LocalDate lastActivityDate,
        long enrollmentCount,
        long completedLessonCount) {
}
