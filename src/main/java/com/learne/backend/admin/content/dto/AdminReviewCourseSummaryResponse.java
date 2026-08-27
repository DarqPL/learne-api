package com.learne.backend.admin.content.dto;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import java.time.Instant;

public record AdminReviewCourseSummaryResponse(
        Long id,
        String name,
        String description,
        CourseType type,
        String level,
        ContentStatus status,
        Long teacherId,
        String teacherName,
        String teacherEmail,
        long topicCount,
        long lessonCount,
        long questionCount,
        Instant updatedAt
) {
    public static AdminReviewCourseSummaryResponse from(Course course) {
        User teacher = course.getTeacher();
        long lessonCount = course.getTopics().stream()
                .flatMap(topic -> topic.getLessons().stream())
                .count();
        long questionCount = course.getTopics().stream()
                .flatMap(topic -> topic.getLessons().stream())
                .flatMap(lesson -> lesson.getQuestions().stream())
                .count();

        return new AdminReviewCourseSummaryResponse(
                course.getId(),
                course.getName(),
                course.getDescription(),
                course.getType(),
                course.getLevel(),
                course.getStatus(),
                teacher.getId(),
                teacher.getFullName(),
                teacher.getEmail(),
                course.getTopics().size(),
                lessonCount,
                questionCount,
                course.getUpdatedAt()
        );
    }
}
