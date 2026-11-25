package com.learne.backend.teacher.content.entity;

import com.learne.backend.teacher.content.enums.CoursePendingReviewSnapshotKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "course_pending_review_snapshots")
public class CoursePendingReviewSnapshot {
    @Id
    @Column(name = "course_id")
    private Long courseId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "course_id")
    private Course course;

    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_kind", nullable = false, length = 32)
    private CoursePendingReviewSnapshotKind snapshotKind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_revision_id", unique = true)
    private CourseRevision courseRevision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_review_id", unique = true)
    private CourseCoverReview coverReview;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        validateTarget();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        validateTarget();
        updatedAt = Instant.now();
    }

    private void validateTarget() {
        boolean revisionTarget = courseRevision != null && coverReview == null
                && snapshotKind == CoursePendingReviewSnapshotKind.COURSE_REVISION;
        boolean coverTarget = coverReview != null && courseRevision == null
                && snapshotKind == CoursePendingReviewSnapshotKind.COVER_ONLY;
        if (!revisionTarget && !coverTarget) {
            throw new IllegalStateException("Pending review snapshot kind and target must match");
        }
    }

    public Long getCourseId() { return courseId; }
    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }
    public CoursePendingReviewSnapshotKind getSnapshotKind() { return snapshotKind; }
    public void setSnapshotKind(CoursePendingReviewSnapshotKind snapshotKind) { this.snapshotKind = snapshotKind; }
    public CourseRevision getCourseRevision() { return courseRevision; }
    public void setCourseRevision(CourseRevision courseRevision) { this.courseRevision = courseRevision; }
    public CourseCoverReview getCoverReview() { return coverReview; }
    public void setCoverReview(CourseCoverReview coverReview) { this.coverReview = coverReview; }
    public Long getSnapshotId() {
        return courseRevision != null ? courseRevision.getId()
                : coverReview != null ? coverReview.getId() : null;
    }
    public long getVersion() { return version; }
}
