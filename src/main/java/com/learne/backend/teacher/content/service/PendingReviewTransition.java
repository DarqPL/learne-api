package com.learne.backend.teacher.content.service;

import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import java.time.Instant;
import java.util.List;

/** Domain mutation only; the durable finalizer owns validation, locking and persistence. */
public final class PendingReviewTransition {
    private PendingReviewTransition() {}
    public static void transitionToPending(Course course, CourseRevision latestRevision, List<CourseRevision> pending) {
        pending.stream().filter(revision -> !revision.getId().equals(latestRevision.getId()))
                .forEach(revision -> revision.setStatus(CourseRevisionStatus.SUPERSEDED));
        latestRevision.setStatus(CourseRevisionStatus.PENDING_REVIEW);
        latestRevision.setSubmittedAt(Instant.now());
        latestRevision.setRejectionReason(null);
        if (course.getPublishedAt() == null) {
            course.setStatus(ContentStatus.PENDING_REVIEW);
            course.setName(latestRevision.getName());
            course.setDescription(latestRevision.getDescription());
            course.setType(latestRevision.getType());
            course.setLevel(latestRevision.getLevel());
            course.setOrderIndex(latestRevision.getOrderIndex());
            course.setRejectionReason(null);
        }
    }
}
