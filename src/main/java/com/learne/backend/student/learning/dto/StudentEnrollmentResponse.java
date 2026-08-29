package com.learne.backend.student.learning.dto;

import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.enums.EnrollmentStatus;
import java.time.Instant;

public record StudentEnrollmentResponse(
        Long id,
        Long courseId,
        String courseName,
        Long teacherId,
        String teacherName,
        EnrollmentStatus status,
        int progressPercent,
        long completedLessonCount,
        int totalLessonCount,
        Instant enrolledAt,
        Instant completedAt
) {
    public static StudentEnrollmentResponse from(CourseEnrollment enrollment) {
        long completed = enrollment.getLessonProgress().stream()
                .filter(progress -> progress.getStatus().name().equals("COMPLETED"))
                .count();
        return new StudentEnrollmentResponse(
                enrollment.getId(),
                enrollment.getCourse().getId(),
                enrollment.getCourse().getName(),
                enrollment.getCourse().getTeacher().getId(),
                enrollment.getCourse().getTeacher().getFullName(),
                enrollment.getStatus(),
                enrollment.getProgressPercent(),
                completed,
                enrollment.getLessonProgress().size(),
                enrollment.getEnrolledAt(),
                enrollment.getCompletedAt()
        );
    }
}
