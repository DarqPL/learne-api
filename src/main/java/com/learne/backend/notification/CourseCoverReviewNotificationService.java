package com.learne.backend.notification;

import com.learne.backend.teacher.content.entity.CourseCoverReview;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.enums.CourseCoverReviewStatus;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseCoverReviewNotificationService {
    private final NotificationRepository notifications;

    public CourseCoverReviewNotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void coverDecision(CourseCoverReview review, CourseCoverReviewStatus decision, String reason) {
        coverDecision(review.getId(), review.getTeacher().getId(), review.getCourse().getId(),
                review.getCourse().getName(), decision, reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void coverDecision(
            long reviewId,
            long teacherId,
            long courseId,
            String courseName,
            CourseCoverReviewStatus decision,
            String reason) {
        if (decision != CourseCoverReviewStatus.APPROVED
                && decision != CourseCoverReviewStatus.REJECTED
                && decision != CourseCoverReviewStatus.SUPERSEDED) {
            throw new IllegalArgumentException("Unsupported cover review notification decision.");
        }
        String type = "COURSE_COVER_REVIEW_" + decision.name();
        String outcome = decision == CourseCoverReviewStatus.SUPERSEDED
                ? "superseded"
                : decision.name().toLowerCase();
        String message = "Course cover for \"" + courseName + "\" was " + outcome + ".";
        if (decision == CourseCoverReviewStatus.REJECTED && reason != null && !reason.isBlank()) {
            message += " Reason: " + reason.trim();
        }
        notifications.insertIfAbsent(new NotificationRepository.Intent(
                UUID.randomUUID(),
                teacherId,
                type,
                null,
                courseId,
                null,
                "course-cover-review:" + reviewId + ":" + decision.name(),
                message));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revisionDecision(CourseRevision revision) {
        if (revision.getCoverImageUrl() == null) {
            return;
        }
        CourseRevisionStatus decision = revision.getStatus();
        if (decision != CourseRevisionStatus.APPROVED && decision != CourseRevisionStatus.REJECTED) {
            throw new IllegalArgumentException("Unsupported revision cover notification decision.");
        }
        String type = "COURSE_REVISION_COVER_" + decision.name();
        String outcome = decision.name().toLowerCase();
        String message = "Course cover for \"" + revision.getName() + "\" was " + outcome + ".";
        if (decision == CourseRevisionStatus.REJECTED
                && revision.getRejectionReason() != null
                && !revision.getRejectionReason().isBlank()) {
            message += " Reason: " + revision.getRejectionReason().trim();
        }
        notifications.insertIfAbsent(new NotificationRepository.Intent(
                UUID.randomUUID(),
                revision.getTeacher().getId(),
                type,
                null,
                revision.getCourse().getId(),
                revision.getId(),
                "course-revision-cover:" + revision.getId() + ":" + decision.name(),
                message));
    }
}
