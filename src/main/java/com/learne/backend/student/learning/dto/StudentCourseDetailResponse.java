package com.learne.backend.student.learning.dto;

import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record StudentCourseDetailResponse(
        Long id,
        String name,
        String description,
        CourseType type,
        String level,
        Long teacherId,
        String teacherName,
        String teacherEmail,
        String teacherAvatarUrl,
        Instant publishedAt,
        boolean enrolled,
        Long activeEnrollmentId,
        List<StudentTopicResponse> topics
) {
    public static StudentCourseDetailResponse from(Course course, CourseEnrollment enrollment) {
        return new StudentCourseDetailResponse(
                course.getId(),
                course.getName(),
                course.getDescription(),
                course.getType(),
                course.getLevel(),
                course.getTeacher().getId(),
                course.getTeacher().getFullName(),
                course.getTeacher().getEmail(),
                course.getTeacher().getAvatarUrl(),
                course.getPublishedAt(),
                enrollment != null,
                enrollment == null ? null : enrollment.getId(),
                course.getTopics().stream()
                        .filter(topic -> topic.getLessons().stream().anyMatch(lesson -> lesson.getStatus() == ContentStatus.APPROVED))
                        .sorted(Comparator.comparingInt(Topic::getOrderIndex).thenComparing(Topic::getId))
                        .map(StudentTopicResponse::from)
                        .toList()
        );
    }
}
