package com.learne.backend.admin.content.dto;

import java.time.Instant;

public record AdminCourseCoverReviewSummaryResponse(
        Long id,
        Long courseId,
        String courseName,
        Long teacherId,
        String teacherName,
        String teacherEmail,
        String currentCoverImageUrl,
        String proposedCoverImageUrl,
        Instant submittedAt) {
}
