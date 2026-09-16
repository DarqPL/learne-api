package com.learne.backend.admin.user.dto;

import com.learne.backend.student.learning.enums.EnrollmentStatus;
import java.time.Instant;

public record AdminStudentEnrollmentResponse(
        Long enrollmentId,
        Long courseId,
        String courseName,
        EnrollmentStatus status,
        int progressPercent,
        Instant enrolledAt) {
}
