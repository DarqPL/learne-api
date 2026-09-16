package com.learne.backend.student.learning.dto;

import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.teacher.content.entity.Course;
import java.time.Instant;

public record StudentActiveCourseResponse(
        Long enrollmentId,
        Long courseId,
        String courseName,
        String teacherName,
        String status,
        int progressPercent,
        Instant enrolledAt
) {
    public static StudentActiveCourseResponse from(CourseEnrollment enrollment) {
        Course course = enrollment.getCourse();
        return new StudentActiveCourseResponse(
                enrollment.getId(),
                course.getId(),
                course.getName(),
                course.getTeacher().getFullName(),
                enrollment.getStatus().name(),
                enrollment.getProgressPercent(),
                enrollment.getEnrolledAt()
        );
    }
}
