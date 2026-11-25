package com.learne.backend.admin.content.dto;

import com.learne.backend.teacher.content.enums.CourseCoverReviewStatus;
import java.time.Instant;

public record AdminCourseCoverReviewDetailResponse(
        Long id,
        Long courseId,
        String courseName,
        Long teacherId,
        String teacherName,
        String teacherEmail,
        String currentCoverImageUrl,
        String proposedCoverImageUrl,
        CourseCoverReviewStatus status,
        String rejectionReason,
        Instant submittedAt,
        Instant reviewedAt) {
}
