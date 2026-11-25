package com.learne.backend.teacher.content.dto;

import com.learne.backend.teacher.content.enums.CourseCoverReviewStatus;
import com.learne.backend.teacher.content.enums.CoursePendingReviewSnapshotKind;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;

public record TeacherCourseCoverStateResponse(
        String publicImageUrl,
        PendingSnapshot pendingSnapshot,
        LatestCoverReview latestCoverReview,
        RevisionCandidate revisionCandidate) {
    public record PendingSnapshot(
            CoursePendingReviewSnapshotKind kind,
            Long id,
            String status,
            String proposedImageUrl) {
    }

    public record LatestCoverReview(
            Long id,
            CourseCoverReviewStatus status,
            String rejectionReason) {
    }

    public record RevisionCandidate(
            Long id,
            CourseRevisionStatus status,
            String proposedImageUrl) {
    }
}
