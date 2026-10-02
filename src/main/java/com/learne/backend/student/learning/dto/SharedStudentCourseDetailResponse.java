package com.learne.backend.student.learning.dto;

import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record SharedStudentCourseDetailResponse(
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
        CoursePricingType pricingType,
        Long priceAmountVnd,
        List<StudentTopicResponse> topics
) {
    public SharedStudentCourseDetailResponse {
        topics = List.copyOf(topics);
    }

    public static SharedStudentCourseDetailResponse from(Course course) {
        return new SharedStudentCourseDetailResponse(
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
                course.getPricingType(),
                course.getPriceAmountVnd(),
                course.getTopics().stream()
                        .filter(topic -> topic.getLessons().stream().anyMatch(lesson -> lesson.getStatus() == ContentStatus.APPROVED))
                        .sorted(Comparator.comparingInt(Topic::getOrderIndex).thenComparing(Topic::getId))
                        .map(StudentTopicResponse::from)
                        .toList()
        );
    }
}
