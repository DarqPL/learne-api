package com.learne.backend.admin.content.service;

import com.learne.backend.admin.content.dto.AdminCourseCoverReviewDetailResponse;
import com.learne.backend.admin.content.dto.AdminCourseCoverReviewSummaryResponse;
import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.cache.CatalogInvalidationOutboxService;
import com.learne.backend.domain.auth.User;
import com.learne.backend.notification.CourseCoverReviewNotificationService;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.shared.pagination.CursorPageResponse;
import com.learne.backend.shared.pagination.CursorTokenService;
import com.learne.backend.teacher.content.audio.service.MediaCleanupProducer;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseCoverReview;
import com.learne.backend.teacher.content.entity.CoursePendingReviewSnapshot;
import com.learne.backend.teacher.content.enums.CourseCoverReviewStatus;
import com.learne.backend.teacher.content.enums.CoursePendingReviewSnapshotKind;
import com.learne.backend.teacher.content.repository.CourseCoverReviewRepository;
import com.learne.backend.teacher.content.repository.CoursePendingReviewSnapshotRepository;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.service.CourseWriteLockService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCourseCoverReviewService {
    private static final String ENDPOINT = "admin-course-cover-reviews";
    private static final String SORT = "submittedAtAscIdAsc";
    private static final String STALE_MESSAGE = "This cover proposal is no longer the current review.";

    private final JdbcTemplate jdbc;
    private final CursorTokenService cursors;
    private final CourseWriteLockService locks;
    private final CourseCoverReviewRepository reviews;
    private final CoursePendingReviewSnapshotRepository snapshots;
    private final CourseRepository courses;
    private final MediaCleanupProducer cleanup;
    private final CourseCoverReviewNotificationService notifications;
    private final CatalogInvalidationOutboxService outbox;
    private final CacheInvalidationService caches;

    public AdminCourseCoverReviewService(
            JdbcTemplate jdbc,
            CursorTokenService cursors,
            CourseWriteLockService locks,
            CourseCoverReviewRepository reviews,
            CoursePendingReviewSnapshotRepository snapshots,
            CourseRepository courses,
            MediaCleanupProducer cleanup,
            CourseCoverReviewNotificationService notifications,
            CatalogInvalidationOutboxService outbox,
            CacheInvalidationService caches) {
        this.jdbc = jdbc;
        this.cursors = cursors;
        this.locks = locks;
        this.reviews = reviews;
        this.snapshots = snapshots;
        this.courses = courses;
        this.cleanup = cleanup;
        this.notifications = notifications;
        this.outbox = outbox;
        this.caches = caches;
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<AdminCourseCoverReviewSummaryResponse> listPending(int size, String cursor) {
        if (size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid size.");
        }
        Map<String, String> filters = Map.of("snapshotKind", "COVER_ONLY", "status", "PENDING_REVIEW");
        List<Object> args = new ArrayList<>();
        String boundary = "";
        if (cursor != null) {
            var values = cursors.decode(cursor, ENDPOINT, SORT, filters).sortValues();
            try {
                boundary = " AND (review.submitted_at, review.id) > (?, ?)";
                args.add(Timestamp.from(Instant.parse(values.get("submittedAt"))));
                args.add(Long.parseLong(values.get("id")));
            } catch (RuntimeException invalidCursor) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid cursor.");
            }
        }
        args.add(size + 1);
        List<AdminCourseCoverReviewSummaryResponse> rows = jdbc.query("""
                SELECT review.id, course.id AS course_id, course.name AS course_name,
                    teacher.id AS teacher_id, teacher.full_name AS teacher_name, teacher.email AS teacher_email,
                    course.image_url AS current_cover_image_url, review.image_url AS proposed_cover_image_url,
                    review.submitted_at
                FROM course_pending_review_snapshots snapshot
                JOIN course_cover_reviews review ON review.id = snapshot.cover_review_id
                JOIN courses course ON course.id = snapshot.course_id
                JOIN users teacher ON teacher.id = review.teacher_id
                WHERE snapshot.snapshot_kind = 'COVER_ONLY'
                    AND review.status = 'PENDING_REVIEW'
                """ + boundary + """
                ORDER BY review.submitted_at ASC, review.id ASC
                LIMIT ?
                """, (rs, row) -> new AdminCourseCoverReviewSummaryResponse(
                rs.getLong("id"),
                rs.getLong("course_id"),
                rs.getString("course_name"),
                rs.getLong("teacher_id"),
                rs.getString("teacher_name"),
                rs.getString("teacher_email"),
                rs.getString("current_cover_image_url"),
                rs.getString("proposed_cover_image_url"),
                rs.getTimestamp("submitted_at").toInstant()), args.toArray());
        boolean hasNext = rows.size() > size;
        List<AdminCourseCoverReviewSummaryResponse> content =
                List.copyOf(hasNext ? rows.subList(0, size) : rows);
        String nextCursor = null;
        if (hasNext) {
            var last = content.getLast();
            nextCursor = cursors.encode(ENDPOINT, SORT, filters, Map.of(
                    "submittedAt", last.submittedAt(),
                    "id", last.id()));
        }
        return new CursorPageResponse<>(content, nextCursor, hasNext, size);
    }

    @Transactional
    public AdminCourseCoverReviewDetailResponse getDetail(Long reviewId) {
        Course course = lockCourse(reviewId);
        return response(course, requireCurrentSnapshot(course.getId(), reviewId).getCoverReview());
    }

    @Transactional
    public AdminCourseCoverReviewDetailResponse approve(User admin, Long reviewId) {
        Course course = lockCourse(reviewId);
        CoursePendingReviewSnapshot snapshot = requireCurrentSnapshot(course.getId(), reviewId);
        CourseCoverReview review = snapshot.getCoverReview();
        String oldUrl = course.getImageUrl();
        String oldPublicId = course.getImagePublicId();

        course.setImageUrl(review.getImageUrl());
        course.setImagePublicId(review.getImagePublicId());
        review.setStatus(CourseCoverReviewStatus.APPROVED);
        review.setReviewedBy(admin);
        review.setReviewedAt(Instant.now());
        review.setRejectionReason(null);
        courses.saveAndFlush(course);
        reviews.saveAndFlush(review);
        snapshots.delete(snapshot);
        notifications.coverDecision(review, CourseCoverReviewStatus.APPROVED, null);
        queueDetachedCover(course.getId(), oldUrl, oldPublicId, review.getImagePublicId());
        if (!java.util.Objects.equals(oldUrl, course.getImageUrl())) {
            outbox.recordPublicCoverChange(course.getId());
            caches.evictGlobalCourseCatalog();
        }
        evictTeacherAndAdmin(course);
        return response(course, review);
    }

    @Transactional
    public AdminCourseCoverReviewDetailResponse reject(User admin, Long reviewId, String reason) {
        Course course = lockCourse(reviewId);
        CoursePendingReviewSnapshot snapshot = requireCurrentSnapshot(course.getId(), reviewId);
        CourseCoverReview review = snapshot.getCoverReview();
        String proposedUrl = review.getImageUrl();
        String proposedPublicId = review.getImagePublicId();
        review.setStatus(CourseCoverReviewStatus.REJECTED);
        review.setReviewedBy(admin);
        review.setReviewedAt(Instant.now());
        review.setRejectionReason(reason);
        AdminCourseCoverReviewDetailResponse response = response(course, review);
        review.setImageUrl(null);
        review.setImagePublicId(null);
        reviews.saveAndFlush(review);
        snapshots.delete(snapshot);
        notifications.coverDecision(review, CourseCoverReviewStatus.REJECTED, reason);
        cleanup.enqueueCourseCover(UUID.randomUUID(), course.getId(),
                proposedUrl, proposedPublicId);
        evictTeacherAndAdmin(course);
        return response;
    }

    private Course lockCourse(Long reviewId) {
        Long courseId = reviews.findCourseIdById(reviewId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Cover review not found."));
        return locks.adminCourse(courseId);
    }

    private CoursePendingReviewSnapshot requireCurrentSnapshot(Long courseId, Long reviewId) {
        CoursePendingReviewSnapshot snapshot = snapshots.findLockedByCourseId(courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, STALE_MESSAGE));
        if (snapshot.getSnapshotKind() != CoursePendingReviewSnapshotKind.COVER_ONLY
                || snapshot.getCoverReview() == null
                || !reviewId.equals(snapshot.getCoverReview().getId())
                || snapshot.getCoverReview().getStatus() != CourseCoverReviewStatus.PENDING_REVIEW) {
            throw new ApiException(HttpStatus.CONFLICT, STALE_MESSAGE);
        }
        return snapshot;
    }

    private void queueDetachedCover(Long courseId, String oldUrl, String oldPublicId, String newPublicId) {
        if (oldPublicId != null && !oldPublicId.equals(newPublicId)) {
            cleanup.enqueueCourseCover(UUID.randomUUID(), courseId, oldUrl, oldPublicId);
        }
    }

    private void evictTeacherAndAdmin(Course course) {
        caches.evictAdminDashboard();
        if (course.getTeacher() != null) {
            caches.evictTeacherDashboard(course.getTeacher().getId());
        }
    }

    private AdminCourseCoverReviewDetailResponse response(Course course, CourseCoverReview review) {
        return new AdminCourseCoverReviewDetailResponse(
                review.getId(),
                course.getId(),
                course.getName(),
                review.getTeacher().getId(),
                review.getTeacher().getFullName(),
                review.getTeacher().getEmail(),
                course.getImageUrl(),
                review.getImageUrl(),
                review.getStatus(),
                review.getRejectionReason(),
                review.getSubmittedAt(),
                review.getReviewedAt());
    }
}
