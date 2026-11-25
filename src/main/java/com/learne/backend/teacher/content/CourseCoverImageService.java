package com.learne.backend.teacher.content;

import com.learne.backend.domain.auth.User;
import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.cache.CatalogInvalidationOutboxService;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.notification.CourseCoverReviewNotificationService;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.content.audio.service.CourseCoverCleanupDecisionService;
import com.learne.backend.teacher.content.audio.service.MediaCleanupProducer;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseCoverReview;
import com.learne.backend.teacher.content.entity.CoursePendingReviewSnapshot;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.dto.TeacherCourseCoverStateResponse;
import com.learne.backend.teacher.content.enums.CourseCoverReviewStatus;
import com.learne.backend.teacher.content.enums.CoursePendingReviewSnapshotKind;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.repository.CourseCoverReviewRepository;
import com.learne.backend.teacher.content.repository.CoursePendingReviewSnapshotRepository;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import com.learne.backend.teacher.content.service.CourseWriteLockService;
import com.learne.backend.teacher.content.service.TeacherContentService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CourseCoverImageService {
    private static final Logger log = LoggerFactory.getLogger(CourseCoverImageService.class);
    private final CourseWriteLockService locks;
    private final StorageService storage;
    private final CourseRepository courses;
    private final MediaCleanupProducer cleanup;
    private final CourseCoverCompensationService compensation;
    private final CourseCoverCleanupDecisionService cleanupDecision;
    private final CourseCoverReviewRepository coverReviews;
    private final CoursePendingReviewSnapshotRepository pendingSnapshots;
    private final CourseRevisionRepository revisions;
    private final TeacherContentService teacherContent;
    private final CourseCoverReviewNotificationService notifications;
    private final CatalogInvalidationOutboxService invalidationOutbox;
    private final CacheInvalidationService caches;

    public CourseCoverImageService(CourseWriteLockService locks, StorageService storage,
                                   CourseRepository courses, MediaCleanupProducer cleanup,
                                   CourseCoverCompensationService compensation,
                                   CourseCoverCleanupDecisionService cleanupDecision,
                                   CourseCoverReviewRepository coverReviews,
                                   CoursePendingReviewSnapshotRepository pendingSnapshots,
                                   CourseRevisionRepository revisions,
                                   TeacherContentService teacherContent,
                                   CourseCoverReviewNotificationService notifications,
                                   CatalogInvalidationOutboxService invalidationOutbox,
                                   CacheInvalidationService caches) {
        this.locks = locks;
        this.storage = storage;
        this.courses = courses;
        this.cleanup = cleanup;
        this.compensation = compensation;
        this.cleanupDecision = cleanupDecision;
        this.coverReviews = coverReviews;
        this.pendingSnapshots = pendingSnapshots;
        this.revisions = revisions;
        this.teacherContent = teacherContent;
        this.notifications = notifications;
        this.invalidationOutbox = invalidationOutbox;
        this.caches = caches;
    }

    @Transactional
    public CourseCoverImageResponse upload(User teacher, Long courseId, MultipartFile file) {
        Course course = locks.teacherCourse(courseId, teacher);
        CoursePendingReviewSnapshot current = pendingSnapshots.findLockedByCourseId(courseId).orElse(null);
        if (current != null
                && current.getSnapshotKind() != CoursePendingReviewSnapshotKind.COVER_ONLY) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A course revision is pending review. Replace its cover through the revision editor.");
        }
        if (revisions.findTopByCourseIdAndStatusOrderByRevisionNumberDesc(
                courseId, CourseRevisionStatus.PENDING_REVIEW).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A course revision is pending review. Replace its cover through the revision editor.");
        }

        StorageUploadResult uploaded = storage.uploadCourseCover(file);
        rejectClaimedUpload(courseId, uploaded);

        CourseCoverReview previous = current == null ? null : current.getCoverReview();
        try {
            CourseCoverReview request = new CourseCoverReview();
            request.setCourse(course);
            request.setTeacher(teacher);
            request.setImageUrl(uploaded.url());
            request.setImagePublicId(uploaded.publicId());
            request.setStatus(CourseCoverReviewStatus.PENDING_REVIEW);
            request = coverReviews.saveAndFlush(request);

            if (previous != null) {
                if (previous.getStatus() != CourseCoverReviewStatus.PENDING_REVIEW) {
                    throw new ApiException(HttpStatus.CONFLICT, "The pending cover proposal is no longer current.");
                }
                String supersededUrl = previous.getImageUrl();
                String supersededPublicId = previous.getImagePublicId();
                previous.setStatus(CourseCoverReviewStatus.SUPERSEDED);
                previous.setReviewedAt(java.time.Instant.now());
                previous.setImageUrl(null);
                previous.setImagePublicId(null);
                coverReviews.saveAndFlush(previous);
                notifications.coverDecision(previous, CourseCoverReviewStatus.SUPERSEDED, null);
                if (supersededPublicId != null && !supersededPublicId.equals(uploaded.publicId())) {
                    cleanup.enqueueCourseCover(UUID.randomUUID(), courseId,
                            supersededUrl, supersededPublicId);
                }
                current.setSnapshotKind(CoursePendingReviewSnapshotKind.COVER_ONLY);
                current.setCourseRevision(null);
                current.setCoverReview(request);
            } else {
                current = new CoursePendingReviewSnapshot();
                current.setCourse(course);
                current.setSnapshotKind(CoursePendingReviewSnapshotKind.COVER_ONLY);
                current.setCoverReview(request);
            }
            pendingSnapshots.saveAndFlush(current);
        } catch (RuntimeException persistenceFailure) {
            enqueueCompensation(courseId, uploaded, persistenceFailure);
            throw persistenceFailure;
        }
        return new CourseCoverImageResponse(uploaded.url());
    }

    @Transactional
    public CourseCoverImageResponse remove(User teacher, Long courseId) {
        Course course = locks.teacherCourse(courseId, teacher);
        String oldUrl = course.getImageUrl();
        String oldPublicId = course.getImagePublicId();
        if (oldUrl == null && oldPublicId == null) {
            return new CourseCoverImageResponse(null);
        }
        course.setImageUrl(null);
        course.setImagePublicId(null);
        courses.saveAndFlush(course);
        cleanup.enqueueCourseCover(UUID.randomUUID(), courseId, oldUrl, oldPublicId);
        if (oldUrl != null) {
            invalidationOutbox.recordPublicCoverChange(courseId);
            caches.evictGlobalCourseCatalog();
        }
        return new CourseCoverImageResponse(null);
    }

    @Transactional
    public CourseCoverImageResponse cancelPending(User teacher, Long courseId) {
        locks.teacherCourse(courseId, teacher);
        CoursePendingReviewSnapshot current = pendingSnapshots.findLockedByCourseId(courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pending cover proposal not found."));
        if (current.getSnapshotKind() != CoursePendingReviewSnapshotKind.COVER_ONLY
                || current.getCoverReview() == null
                || current.getCoverReview().getStatus() != CourseCoverReviewStatus.PENDING_REVIEW) {
            throw new ApiException(HttpStatus.CONFLICT, "The current review is not a cover-only proposal.");
        }
        CourseCoverReview request = current.getCoverReview();
        String requestUrl = request.getImageUrl();
        String requestPublicId = request.getImagePublicId();
        request.setStatus(CourseCoverReviewStatus.CANCELLED);
        request.setReviewedAt(java.time.Instant.now());
        request.setImageUrl(null);
        request.setImagePublicId(null);
        coverReviews.saveAndFlush(request);
        pendingSnapshots.delete(current);
        cleanup.enqueueCourseCover(UUID.randomUUID(), courseId,
                requestUrl, requestPublicId);
        return new CourseCoverImageResponse(null);
    }

    @Transactional
    public CourseCoverImageResponse uploadRevision(User teacher, Long courseId, MultipartFile file) {
        locks.teacherCourse(courseId, teacher);
        StorageUploadResult uploaded = storage.uploadCourseCover(file);
        rejectClaimedUpload(courseId, uploaded);
        try {
            teacherContent.replaceRevisionCover(teacher, courseId, uploaded.url(), uploaded.publicId());
        } catch (RuntimeException persistenceFailure) {
            enqueueCompensation(courseId, uploaded, persistenceFailure);
            throw persistenceFailure;
        }
        return new CourseCoverImageResponse(uploaded.url());
    }

    private void rejectClaimedUpload(Long courseId, StorageUploadResult uploaded) {
        boolean claimed;
        try {
            claimed = cleanupDecision.isPublicIdClaimed(uploaded.publicId());
        } catch (RuntimeException claimCheckFailure) {
            enqueueCompensation(courseId, uploaded, claimCheckFailure);
            throw claimCheckFailure;
        }
        if (claimed) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Ảnh vừa tải đang chờ dọn dẹp. Vui lòng thử lại.");
        }
    }

    @Transactional
    public CourseCoverImageResponse removeRevision(User teacher, Long courseId) {
        teacherContent.replaceRevisionCover(teacher, courseId, null, null);
        return new CourseCoverImageResponse(null);
    }

    @Transactional
    public TeacherCourseCoverStateResponse state(User teacher, Long courseId) {
        Course course = locks.teacherCourse(courseId, teacher);
        CoursePendingReviewSnapshot snapshot = pendingSnapshots.findById(courseId).orElse(null);
        TeacherCourseCoverStateResponse.PendingSnapshot pending = null;
        if (snapshot != null && snapshot.getSnapshotKind() == CoursePendingReviewSnapshotKind.COVER_ONLY) {
            CourseCoverReview review = snapshot.getCoverReview();
            if (review != null && review.getStatus() == CourseCoverReviewStatus.PENDING_REVIEW) {
                pending = new TeacherCourseCoverStateResponse.PendingSnapshot(
                        snapshot.getSnapshotKind(), review.getId(), review.getStatus().name(), review.getImageUrl());
            }
        } else if (snapshot != null && snapshot.getSnapshotKind() == CoursePendingReviewSnapshotKind.COURSE_REVISION) {
            CourseRevision revision = snapshot.getCourseRevision();
            if (revision != null && revision.getStatus() == CourseRevisionStatus.PENDING_REVIEW) {
                pending = new TeacherCourseCoverStateResponse.PendingSnapshot(
                        snapshot.getSnapshotKind(), revision.getId(), revision.getStatus().name(),
                        revision.getCoverImageUrl());
            }
        }

        CourseCoverReview latestReview = coverReviews.findTopByCourse_IdOrderBySubmittedAtDescIdDesc(courseId)
                .orElse(null);
        CourseRevision latestRevision = revisions.findTopByCourseIdOrderByRevisionNumberDesc(courseId)
                .orElse(null);
        return new TeacherCourseCoverStateResponse(
                course.getImageUrl(),
                pending,
                latestReview == null ? null : new TeacherCourseCoverStateResponse.LatestCoverReview(
                        latestReview.getId(), latestReview.getStatus(), latestReview.getRejectionReason()),
                latestRevision == null || latestRevision.getCoverImageUrl() == null
                        ? null
                        : new TeacherCourseCoverStateResponse.RevisionCandidate(
                                latestRevision.getId(), latestRevision.getStatus(), latestRevision.getCoverImageUrl()));
    }

    private void enqueueCompensation(Long courseId, StorageUploadResult uploaded, RuntimeException persistenceFailure) {
        try {
            compensation.enqueueFailedPersistence(courseId, uploaded.url(), uploaded.publicId());
        } catch (RuntimeException compensationFailure) {
            log.error("Course cover compensation enqueue failed: courseId={}, publicId={}",
                    courseId, uploaded.publicId(), compensationFailure);
            persistenceFailure.addSuppressed(compensationFailure);
        }
    }
}
