package com.learne.backend.teacher.content.dto;

import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CoursePriceStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import java.util.Comparator;
import java.util.List;

public record CourseResponse(
        Long id,
        String name,
        String description,
        CourseType type,
        String level,
        int orderIndex,
        ContentStatus status,
        CoursePricingType pricingType,
        Long priceAmountVnd,
        Long proposedPriceAmountVnd,
        CoursePriceStatus priceStatus,
        String rejectionReason,
        Long latestRevisionId,
        CourseRevisionStatus latestRevisionStatus,
        boolean hasEditableRevision,
        List<TopicResponse> topics
) {
    public static CourseResponse from(Course course) {
        return from(course, null);
    }

    public static CourseResponse from(Course course, CourseRevision latestRevision) {
        return new CourseResponse(
                course.getId(),
                course.getName(),
                course.getDescription(),
                course.getType(),
                course.getLevel(),
                course.getOrderIndex(),
                course.getStatus(),
                course.getPricingType(),
                course.getPriceAmountVnd(),
                course.getProposedPriceAmountVnd(),
                course.getPriceStatus(),
                course.getRejectionReason(),
                latestRevision == null ? null : latestRevision.getId(),
                latestRevision == null ? null : latestRevision.getStatus(),
                latestRevision != null && isEditable(latestRevision.getStatus()),
                course.getTopics().stream()
                        .sorted(Comparator.comparingInt(Topic::getOrderIndex).thenComparing(Topic::getId))
                        .map(TopicResponse::from)
                        .toList()
                );
    }

    public static CourseResponse fromRevisionBackedCourse(Course course, CourseRevision revision) {
        ContentStatus revisionContentStatus = toContentStatus(revision.getStatus());
        return new CourseResponse(
                course.getId(),
                revision.getName(),
                revision.getDescription(),
                revision.getType(),
                revision.getLevel(),
                revision.getOrderIndex(),
                course.getStatus(),
                revision.getPricingType(),
                course.getPriceAmountVnd(),
                revision.getProposedPriceAmountVnd(),
                course.getPriceStatus(),
                revision.getRejectionReason(),
                revision.getId(),
                revision.getStatus(),
                isEditable(revision.getStatus()),
                revision.getTopics().stream()
                        .sorted(Comparator.comparingInt(TopicRevision::getOrderIndex)
                                .thenComparing(TopicRevision::getId))
                        .map(topic -> TopicResponse.from(topic, revisionContentStatus))
                        .toList()
        );
    }

    private static ContentStatus toContentStatus(CourseRevisionStatus status) {
        return switch (status) {
            case DRAFT, SUPERSEDED -> ContentStatus.DRAFT;
            case PENDING_REVIEW -> ContentStatus.PENDING_REVIEW;
            case APPROVED -> ContentStatus.APPROVED;
            case REJECTED -> ContentStatus.REJECTED;
        };
    }

    private static boolean isEditable(CourseRevisionStatus status) {
        return status == CourseRevisionStatus.DRAFT || status == CourseRevisionStatus.REJECTED;
    }
}
