package com.learne.backend.teacher.dashboard.dto;

import com.learne.backend.teacher.content.entity.Course;
import java.time.Instant;

public record TeacherRecentCourseResponse(Long id, String name, String status, Instant updatedAt) {
    public static TeacherRecentCourseResponse from(Course course) {
        return new TeacherRecentCourseResponse(
                course.getId(),
                course.getName(),
                course.getStatus().name(),
                course.getUpdatedAt()
        );
    }
}
