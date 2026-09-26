package com.learne.backend.admin.content.dto;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CoursePriceStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseType;
import java.time.Instant;

public record AdminReviewCourseSummaryResponse(
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

    public static AdminReviewCourseSummaryResponse from(CourseRevision revision) {
        User teacher = revision.getTeacher();
        long lessonCount = revision.getTopics().stream()
                .flatMap(topic -> topic.getLessons().stream())
                .count();
        long questionCount = revision.getTopics().stream()
                .flatMap(topic -> topic.getLessons().stream())
                .flatMap(lesson -> lesson.getQuestions().stream())
                .count();

        return new AdminReviewCourseSummaryResponse(
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
                ContentStatus.valueOf(revision.getStatus().name()),
                teacher.getId(),
                teacher.getFullName(),
                teacher.getEmail(),
                revision.getTopics().size(),
                lessonCount,
                questionCount,
                revision.getUpdatedAt()
        );
    }
}
