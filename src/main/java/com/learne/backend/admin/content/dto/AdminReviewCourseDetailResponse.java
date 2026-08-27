package com.learne.backend.admin.content.dto;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record AdminReviewCourseDetailResponse(
        Long id,
        String name,
        String description,
        CourseType type,
        String level,
        int orderIndex,
        ContentStatus status,
        String rejectionReason,
        Long teacherId,
        String teacherName,
        String teacherEmail,
        Long reviewedByAdminId,
        Instant reviewedAt,
        Instant publishedAt,
        Instant updatedAt,
        List<AdminReviewTopicResponse> topics
) {
    public static AdminReviewCourseDetailResponse from(Course course) {
        User teacher = course.getTeacher();
        User reviewedBy = course.getReviewedBy();

        return new AdminReviewCourseDetailResponse(
                course.getId(),
                course.getName(),
                course.getDescription(),
                course.getType(),
                course.getLevel(),
                course.getOrderIndex(),
                course.getStatus(),
                course.getRejectionReason(),
                teacher.getId(),
                teacher.getFullName(),
                teacher.getEmail(),
                reviewedBy == null ? null : reviewedBy.getId(),
                course.getReviewedAt(),
                course.getPublishedAt(),
                course.getUpdatedAt(),
                course.getTopics().stream()
                        .sorted(Comparator.comparingInt(Topic::getOrderIndex).thenComparing(Topic::getId))
                        .map(AdminReviewTopicResponse::from)
                        .toList()
        );
    }
}
