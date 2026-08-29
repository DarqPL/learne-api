package com.learne.backend.student.learning.dto;

import com.learne.backend.teacher.content.entity.Course;

public record StudentProgressCourseResponse(
        Long id,
        String name,
        String level,
        String type,
        Long teacherId,
        String teacherName
) {
    public static StudentProgressCourseResponse from(Course course) {
        return new StudentProgressCourseResponse(
                course.getId(),
                course.getName(),
                course.getLevel(),
                course.getType().name(),
                course.getTeacher().getId(),
                course.getTeacher().getFullName()
        );
    }
}
