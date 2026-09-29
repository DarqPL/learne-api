package com.learne.backend.admin.content.dto;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CoursePriceStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record AdminReviewCourseDetailResponse(
        Long id,
        Long revisionId,
        int revisionNumber,
        String name,
        String description,
        CourseType type,
        String level,
        CoursePricingType pricingType,
        Long priceAmountVnd,
        Long proposedPriceAmountVnd,
        CoursePriceStatus priceStatus,
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
                null,
                0,
                course.getName(),
                course.getDescription(),
                course.getType(),
                course.getLevel(),
                course.getPricingType(),
                course.getPriceAmountVnd(),
                course.getProposedPriceAmountVnd(),
                course.getPriceStatus(),
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

    public static AdminReviewCourseDetailResponse from(Course course, CourseRevision revision) {
        User teacher = course.getTeacher();
        User reviewedBy = course.getReviewedBy();

        return new AdminReviewCourseDetailResponse(
                course.getId(),
                revision.getId(),
                revision.getRevisionNumber(),
                course.getName(),
                course.getDescription(),
                course.getType(),
                course.getLevel(),
                course.getPricingType(),
                course.getPriceAmountVnd(),
                course.getProposedPriceAmountVnd(),
                course.getPriceStatus(),
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

    public static AdminReviewCourseDetailResponse from(CourseRevision revision) {
        User teacher = revision.getTeacher();
        User reviewedBy = revision.getReviewedBy();

        return new AdminReviewCourseDetailResponse(
                revision.getCourse().getId(),
                revision.getId(),
                revision.getRevisionNumber(),
                revision.getName(),
                revision.getDescription(),
                revision.getType(),
                revision.getLevel(),
                revision.getPricingType(),
                revision.getCourse().getPriceAmountVnd(),
                revision.getProposedPriceAmountVnd(),
                revision.getCourse().getPriceStatus(),
                revision.getOrderIndex(),
                ContentStatus.valueOf(revision.getStatus().name()),
                revision.getRejectionReason(),
                teacher.getId(),
                teacher.getFullName(),
                teacher.getEmail(),
                reviewedBy == null ? null : reviewedBy.getId(),
                revision.getReviewedAt(),
                revision.getPublishedAt(),
                revision.getUpdatedAt(),
                revision.getTopics().stream()
                        .sorted(Comparator.comparingInt(TopicRevision::getOrderIndex).thenComparing(TopicRevision::getId))
                        .map(AdminReviewTopicResponse::from)
                        .toList()
        );
    }
}
