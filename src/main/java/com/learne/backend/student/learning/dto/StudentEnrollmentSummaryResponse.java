package com.learne.backend.student.learning.dto;

public record StudentEnrollmentSummaryResponse(
        long totalEnrollments,
        long activeEnrollments,
        long completedLessons
) {
}
