package com.learne.backend.teacher.content;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

import com.learne.backend.domain.auth.User;
import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.cache.CatalogInvalidationOutboxService;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.notification.CourseCoverReviewNotificationService;
import com.learne.backend.teacher.content.audio.service.MediaCleanupProducer;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseCoverReview;
import com.learne.backend.teacher.content.entity.CoursePendingReviewSnapshot;
import com.learne.backend.teacher.content.enums.CourseCoverReviewStatus;
import com.learne.backend.teacher.content.enums.CoursePendingReviewSnapshotKind;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.repository.CourseCoverReviewRepository;
import com.learne.backend.teacher.content.repository.CoursePendingReviewSnapshotRepository;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import com.learne.backend.teacher.content.service.CourseWriteLockService;
import com.learne.backend.teacher.content.service.TeacherContentService;
import com.learne.backend.teacher.content.audio.service.CourseCoverCleanupDecisionService;
import com.learne.backend.teacher.content.audio.dto.CleanupDecision;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class CourseCoverImageServiceTests {
    @Test
    void uploadCreatesPendingProposalWithoutChangingPublicCover() {
        CourseWriteLockService locks = mock(CourseWriteLockService.class);
        StorageService storage = mock(StorageService.class);
        CourseRepository courses = mock(CourseRepository.class);
        MediaCleanupProducer cleanup = mock(MediaCleanupProducer.class);
        CourseCoverReviewRepository coverReviews = mock(CourseCoverReviewRepository.class);
        CoursePendingReviewSnapshotRepository pendingSnapshots = mock(CoursePendingReviewSnapshotRepository.class);
        CourseRevisionRepository revisions = mock(CourseRevisionRepository.class);
        CourseCoverImageService service =
                new CourseCoverImageService(locks, storage, courses, cleanup,
                        mock(CourseCoverCompensationService.class),
                        mock(CourseCoverCleanupDecisionService.class), coverReviews, pendingSnapshots, revisions,
                        mock(TeacherContentService.class), mock(CourseCoverReviewNotificationService.class),
                        mock(CatalogInvalidationOutboxService.class), mock(CacheInvalidationService.class));

        User teacher = new User();
        Course course = new Course();
        course.setTeacher(teacher);
        course.setImageUrl("https://example.test/old.jpg");
        course.setImagePublicId("learne/course-covers/old");
        when(locks.teacherCourse(10L, teacher)).thenReturn(course);
        when(pendingSnapshots.findLockedByCourseId(10L)).thenReturn(Optional.empty());
        when(revisions.findTopByCourseIdAndStatusOrderByRevisionNumberDesc(
                10L, CourseRevisionStatus.PENDING_REVIEW)).thenReturn(Optional.empty());
        when(storage.uploadCourseCover(any())).thenReturn(
                new StorageUploadResult("https://example.test/new.jpg", "learne/course-covers/new"));
        when(coverReviews.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(pendingSnapshots.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CourseCoverImageResponse response = service.upload(
                teacher, 10L,
                new MockMultipartFile("file", "cover.png", "image/png", new byte[8]));

        assertThat(response.imageUrl()).isEqualTo("https://example.test/new.jpg");
        assertThat(response).hasOnlyFields("imageUrl");
        assertThat(course.getImageUrl()).isEqualTo("https://example.test/old.jpg");
        verify(cleanup, never()).enqueueCourseCover(any(UUID.class), eq(10L), any(), any());
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(CourseCoverReview.class);
        verify(coverReviews).saveAndFlush(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getStatus()).isEqualTo(CourseCoverReviewStatus.PENDING_REVIEW);
        verify(pendingSnapshots).saveAndFlush(any(CoursePendingReviewSnapshot.class));
    }

    @Test
    void repeatedRemoveIsSuccessfulNoOp() {
        CourseWriteLockService locks = mock(CourseWriteLockService.class);
        StorageService storage = mock(StorageService.class);
        CourseRepository courses = mock(CourseRepository.class);
        MediaCleanupProducer cleanup = mock(MediaCleanupProducer.class);
        CourseCoverImageService service = createService(locks, storage, courses, cleanup);

        User teacher = new User();
        Course course = new Course();
        course.setTeacher(teacher);
        when(locks.teacherCourse(11L, teacher)).thenReturn(course);

        CourseCoverImageResponse response = service.remove(teacher, 11L);

        assertThat(response.imageUrl()).isNull();
    }

    @Test
    void removingPublicCoverKeepsPendingProposalAndRegistryCurrent() {
        CourseWriteLockService locks = mock(CourseWriteLockService.class);
        CourseRepository courses = mock(CourseRepository.class);
        CourseCoverReviewRepository reviews = mock(CourseCoverReviewRepository.class);
        CoursePendingReviewSnapshotRepository snapshots = mock(CoursePendingReviewSnapshotRepository.class);
        MediaCleanupProducer cleanup = mock(MediaCleanupProducer.class);
        CatalogInvalidationOutboxService outbox = mock(CatalogInvalidationOutboxService.class);
        CacheInvalidationService caches = mock(CacheInvalidationService.class);
        Course course = new Course();
        course.setImageUrl("https://example.test/approved.jpg");
        course.setImagePublicId("approved-cover");
        CourseCoverReview request = new CourseCoverReview();
        request.setImageUrl("https://example.test/pending.jpg");
        request.setImagePublicId("pending-cover");
        request.setStatus(CourseCoverReviewStatus.PENDING_REVIEW);
        CoursePendingReviewSnapshot current = new CoursePendingReviewSnapshot();
        current.setCourse(course);
        current.setSnapshotKind(CoursePendingReviewSnapshotKind.COVER_ONLY);
        current.setCoverReview(request);
        User teacher = new User();
        when(locks.teacherCourse(18L, teacher)).thenReturn(course);
        when(snapshots.findLockedByCourseId(18L)).thenReturn(Optional.of(current));
        CourseCoverImageService service = new CourseCoverImageService(
                locks, mock(StorageService.class), courses, cleanup,
                mock(CourseCoverCompensationService.class), mock(CourseCoverCleanupDecisionService.class),
                reviews, snapshots, mock(CourseRevisionRepository.class), mock(TeacherContentService.class),
                mock(CourseCoverReviewNotificationService.class), outbox, caches);

        service.remove(teacher, 18L);

        assertThat(course.getImageUrl()).isNull();
        assertThat(course.getImagePublicId()).isNull();
        assertThat(request.getStatus()).isEqualTo(CourseCoverReviewStatus.PENDING_REVIEW);
        assertThat(current.getCoverReview()).isSameAs(request);
        verify(snapshots, never()).delete(current);
        verify(cleanup).enqueueCourseCover(any(UUID.class), eq(18L),
                eq("https://example.test/approved.jpg"), eq("approved-cover"));
        verify(outbox).recordPublicCoverChange(18L);
        verify(caches).evictGlobalCourseCatalog();
    }

    @Test
    void replacingPendingProposalSupersedesItAndKeepsApprovedCover() {
        CourseWriteLockService locks = mock(CourseWriteLockService.class);
        StorageService storage = mock(StorageService.class);
        CourseRepository courses = mock(CourseRepository.class);
        MediaCleanupProducer cleanup = mock(MediaCleanupProducer.class);
        CourseCoverReviewRepository reviews = mock(CourseCoverReviewRepository.class);
        CoursePendingReviewSnapshotRepository snapshots = mock(CoursePendingReviewSnapshotRepository.class);
        CourseRevisionRepository revisions = mock(CourseRevisionRepository.class);
        CourseCoverReviewNotificationService notifications = mock(CourseCoverReviewNotificationService.class);
        CourseCoverImageService service = new CourseCoverImageService(
                locks, storage, courses, cleanup, mock(CourseCoverCompensationService.class),
                mock(CourseCoverCleanupDecisionService.class), reviews, snapshots, revisions,
                mock(TeacherContentService.class), notifications,
                mock(CatalogInvalidationOutboxService.class), mock(CacheInvalidationService.class));

        User teacher = new User();
        Course course = new Course();
        course.setImageUrl("https://example.test/approved.jpg");
        course.setImagePublicId("approved-cover");
        CourseCoverReview previous = new CourseCoverReview();
        previous.setImageUrl("https://example.test/pending.jpg");
        previous.setImagePublicId("pending-cover");
        previous.setStatus(CourseCoverReviewStatus.PENDING_REVIEW);
        CoursePendingReviewSnapshot current = new CoursePendingReviewSnapshot();
        current.setCourse(course);
        current.setSnapshotKind(CoursePendingReviewSnapshotKind.COVER_ONLY);
        current.setCoverReview(previous);
        when(locks.teacherCourse(15L, teacher)).thenReturn(course);
        when(snapshots.findLockedByCourseId(15L)).thenReturn(Optional.of(current));
        when(revisions.findTopByCourseIdAndStatusOrderByRevisionNumberDesc(
                15L, CourseRevisionStatus.PENDING_REVIEW)).thenReturn(Optional.empty());
        when(storage.uploadCourseCover(any())).thenReturn(
                new StorageUploadResult("https://example.test/next.jpg", "next-cover"));
        when(reviews.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshots.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.upload(teacher, 15L,
                new MockMultipartFile("file", "cover.png", "image/png", new byte[8]));

        assertThat(course.getImageUrl()).isEqualTo("https://example.test/approved.jpg");
        assertThat(previous.getStatus()).isEqualTo(CourseCoverReviewStatus.SUPERSEDED);
        assertThat(previous.getImageUrl()).isNull();
        assertThat(previous.getImagePublicId()).isNull();
        assertThat(current.getCoverReview().getImagePublicId()).isEqualTo("next-cover");
        verify(cleanup).enqueueCourseCover(any(UUID.class), eq(15L),
                eq("https://example.test/pending.jpg"), eq("pending-cover"));
        verify(notifications).coverDecision(previous, CourseCoverReviewStatus.SUPERSEDED, null);
    }

    @Test
    void cancellingPendingProposalDoesNotRemoveApprovedCover() {
        CourseWriteLockService locks = mock(CourseWriteLockService.class);
        Course course = new Course();
        course.setImageUrl("https://example.test/approved.jpg");
        course.setImagePublicId("approved-cover");
        User teacher = new User();
        CourseCoverReviewRepository reviews = mock(CourseCoverReviewRepository.class);
        CoursePendingReviewSnapshotRepository snapshots = mock(CoursePendingReviewSnapshotRepository.class);
        MediaCleanupProducer cleanup = mock(MediaCleanupProducer.class);
        CourseCoverImageService service = new CourseCoverImageService(
                locks, mock(StorageService.class), mock(CourseRepository.class), cleanup,
                mock(CourseCoverCompensationService.class), mock(CourseCoverCleanupDecisionService.class),
                reviews, snapshots, mock(CourseRevisionRepository.class), mock(TeacherContentService.class),
                mock(CourseCoverReviewNotificationService.class),
                mock(CatalogInvalidationOutboxService.class), mock(CacheInvalidationService.class));
        CourseCoverReview request = new CourseCoverReview();
        request.setImageUrl("https://example.test/pending.jpg");
        request.setImagePublicId("pending-cover");
        request.setStatus(CourseCoverReviewStatus.PENDING_REVIEW);
        CoursePendingReviewSnapshot current = new CoursePendingReviewSnapshot();
        current.setCourse(course);
        current.setSnapshotKind(CoursePendingReviewSnapshotKind.COVER_ONLY);
        current.setCoverReview(request);
        when(locks.teacherCourse(16L, teacher)).thenReturn(course);
        when(snapshots.findLockedByCourseId(16L)).thenReturn(Optional.of(current));

        service.cancelPending(teacher, 16L);

        assertThat(request.getStatus()).isEqualTo(CourseCoverReviewStatus.CANCELLED);
        assertThat(request.getImageUrl()).isNull();
        assertThat(request.getImagePublicId()).isNull();
        assertThat(course.getImageUrl()).isEqualTo("https://example.test/approved.jpg");
        verify(snapshots).delete(current);
        verify(cleanup).enqueueCourseCover(any(UUID.class), eq(16L),
                eq("https://example.test/pending.jpg"), eq("pending-cover"));
    }

    @Test
    void cancellingStaleRevisionSnapshotReturnsConflictWithoutDeletingIt() {
        CourseWriteLockService locks = mock(CourseWriteLockService.class);
        CoursePendingReviewSnapshotRepository snapshots = mock(CoursePendingReviewSnapshotRepository.class);
        CoursePendingReviewSnapshot current = new CoursePendingReviewSnapshot();
        current.setSnapshotKind(CoursePendingReviewSnapshotKind.COURSE_REVISION);
        User teacher = new User();
        when(locks.teacherCourse(19L, teacher)).thenReturn(new Course());
        when(snapshots.findLockedByCourseId(19L)).thenReturn(Optional.of(current));
        CourseCoverImageService service = new CourseCoverImageService(
                locks, mock(StorageService.class), mock(CourseRepository.class),
                mock(MediaCleanupProducer.class), mock(CourseCoverCompensationService.class),
                mock(CourseCoverCleanupDecisionService.class), mock(CourseCoverReviewRepository.class),
                snapshots, mock(CourseRevisionRepository.class), mock(TeacherContentService.class),
                mock(CourseCoverReviewNotificationService.class),
                mock(CatalogInvalidationOutboxService.class), mock(CacheInvalidationService.class));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.cancelPending(teacher, 19L)))
                .isInstanceOf(ApiException.class);
        verify(snapshots, never()).delete(current);
    }

    @Test
    void uploadRejectsNonOwnerBeforeUploading() {
        CourseWriteLockService locks = mock(CourseWriteLockService.class);
        StorageService storage = mock(StorageService.class);
        CourseCoverImageService service = createService(
                locks, storage, mock(CourseRepository.class), mock(MediaCleanupProducer.class));
        User teacher = new User();
        when(locks.teacherCourse(17L, teacher))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "Course not found."));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.upload(teacher, 17L,
                        new MockMultipartFile("file", "cover.png", "image/png", new byte[8]))))
                .isInstanceOf(ApiException.class);
        org.mockito.Mockito.verifyNoInteractions(storage);
    }

    @Test
    void saveFailureEnqueuesCompensationInIndependentTransaction() {
        CourseWriteLockService locks = mock(CourseWriteLockService.class);
        StorageService storage = mock(StorageService.class);
        CourseRepository courses = mock(CourseRepository.class);
        MediaCleanupProducer cleanup = mock(MediaCleanupProducer.class);
        CourseCoverCompensationService compensation = mock(CourseCoverCompensationService.class);
        CourseCoverReviewRepository coverReviews = mock(CourseCoverReviewRepository.class);
        CourseCoverImageService service =
                new CourseCoverImageService(locks, storage, courses, cleanup, compensation,
                        mock(CourseCoverCleanupDecisionService.class), coverReviews,
                        mock(CoursePendingReviewSnapshotRepository.class),
                        mock(CourseRevisionRepository.class), mock(TeacherContentService.class),
                        mock(CourseCoverReviewNotificationService.class),
                        mock(CatalogInvalidationOutboxService.class), mock(CacheInvalidationService.class));
        User teacher = new User();
        Course course = new Course();
        course.setTeacher(teacher);
        when(locks.teacherCourse(12L, teacher)).thenReturn(course);
        when(storage.uploadCourseCover(any())).thenReturn(
                new StorageUploadResult("https://example.test/new.jpg", "learne/course-covers/new"));
        when(coverReviews.saveAndFlush(any())).thenThrow(new RuntimeException("DB failed"));

        assertThat(org.assertj.core.api.Assertions.catchRuntimeException(
                () -> service.upload(teacher, 12L,
                        new org.springframework.mock.web.MockMultipartFile("file", "cover.png", "image/png", new byte[8]))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB failed");
        verify(compensation).enqueueFailedPersistence(
                12L, "https://example.test/new.jpg", "learne/course-covers/new");
    }

    @Test
    void claimCheckFailureEnqueuesCompensation() {
        CourseWriteLockService locks = mock(CourseWriteLockService.class);
        StorageService storage = mock(StorageService.class);
        CourseCoverCompensationService compensation = mock(CourseCoverCompensationService.class);
        CourseCoverCleanupDecisionService cleanupDecision = mock(CourseCoverCleanupDecisionService.class);
        CourseCoverImageService service = new CourseCoverImageService(
                locks, storage, mock(CourseRepository.class), mock(MediaCleanupProducer.class),
                compensation, cleanupDecision, mock(CourseCoverReviewRepository.class),
                mock(CoursePendingReviewSnapshotRepository.class), mock(CourseRevisionRepository.class),
                mock(TeacherContentService.class), mock(CourseCoverReviewNotificationService.class),
                mock(CatalogInvalidationOutboxService.class), mock(CacheInvalidationService.class));
        User teacher = new User();
        when(locks.teacherCourse(20L, teacher)).thenReturn(new Course());
        when(storage.uploadCourseCover(any())).thenReturn(
                new StorageUploadResult("https://example.test/new.jpg", "learne/course-covers/new"));
        org.mockito.Mockito.doThrow(new RuntimeException("claim lookup failed"))
                .when(cleanupDecision).isPublicIdClaimed("learne/course-covers/new");

        assertThat(org.assertj.core.api.Assertions.catchRuntimeException(
                () -> service.upload(teacher, 20L,
                        new MockMultipartFile("file", "cover.png", "image/png", new byte[8]))))
                .hasMessage("claim lookup failed");
        verify(compensation).enqueueFailedPersistence(
                20L, "https://example.test/new.jpg", "learne/course-covers/new");
    }

    @Test
    void supersededCleanupFailureEnqueuesCompensation() {
        CourseWriteLockService locks = mock(CourseWriteLockService.class);
        StorageService storage = mock(StorageService.class);
        MediaCleanupProducer cleanup = mock(MediaCleanupProducer.class);
        CourseCoverCompensationService compensation = mock(CourseCoverCompensationService.class);
        CourseCoverReviewRepository reviews = mock(CourseCoverReviewRepository.class);
        CoursePendingReviewSnapshotRepository snapshots = mock(CoursePendingReviewSnapshotRepository.class);
        CourseRevisionRepository revisions = mock(CourseRevisionRepository.class);
        CourseCoverImageService service = new CourseCoverImageService(
                locks, storage, mock(CourseRepository.class), cleanup, compensation,
                mock(CourseCoverCleanupDecisionService.class), reviews, snapshots, revisions,
                mock(TeacherContentService.class), mock(CourseCoverReviewNotificationService.class),
                mock(CatalogInvalidationOutboxService.class), mock(CacheInvalidationService.class));
        User teacher = new User();
        Course course = new Course();
        CourseCoverReview previous = new CourseCoverReview();
        previous.setStatus(CourseCoverReviewStatus.PENDING_REVIEW);
        previous.setImageUrl("https://example.test/old.jpg");
        previous.setImagePublicId("learne/course-covers/old");
        CoursePendingReviewSnapshot current = new CoursePendingReviewSnapshot();
        current.setSnapshotKind(CoursePendingReviewSnapshotKind.COVER_ONLY);
        current.setCoverReview(previous);
        when(locks.teacherCourse(21L, teacher)).thenReturn(course);
        when(snapshots.findLockedByCourseId(21L)).thenReturn(Optional.of(current));
        when(revisions.findTopByCourseIdAndStatusOrderByRevisionNumberDesc(
                21L, CourseRevisionStatus.PENDING_REVIEW)).thenReturn(Optional.empty());
        when(storage.uploadCourseCover(any())).thenReturn(
                new StorageUploadResult("https://example.test/new.jpg", "learne/course-covers/new"));
        when(reviews.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshots.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("cleanup enqueue failed")).when(cleanup)
                .enqueueCourseCover(any(UUID.class), eq(21L),
                        eq("https://example.test/old.jpg"), eq("learne/course-covers/old"));

        assertThat(org.assertj.core.api.Assertions.catchRuntimeException(
                () -> service.upload(teacher, 21L,
                        new MockMultipartFile("file", "cover.png", "image/png", new byte[8]))))
                .hasMessage("cleanup enqueue failed");
        verify(compensation).enqueueFailedPersistence(
                21L, "https://example.test/new.jpg", "learne/course-covers/new");
    }

    @Test
    void compensationEnqueueFailureDoesNotHidePersistenceFailure() {
        CourseWriteLockService locks = mock(CourseWriteLockService.class);
        StorageService storage = mock(StorageService.class);
        CourseRepository courses = mock(CourseRepository.class);
        MediaCleanupProducer cleanup = mock(MediaCleanupProducer.class);
        CourseCoverCompensationService compensation = mock(CourseCoverCompensationService.class);
        CourseCoverReviewRepository coverReviews = mock(CourseCoverReviewRepository.class);
        CourseCoverImageService service =
                new CourseCoverImageService(locks, storage, courses, cleanup, compensation,
                        mock(CourseCoverCleanupDecisionService.class), coverReviews,
                        mock(CoursePendingReviewSnapshotRepository.class),
                        mock(CourseRevisionRepository.class), mock(TeacherContentService.class),
                        mock(CourseCoverReviewNotificationService.class),
                        mock(CatalogInvalidationOutboxService.class), mock(CacheInvalidationService.class));
        User teacher = new User();
        Course course = new Course();
        course.setTeacher(teacher);
        when(locks.teacherCourse(13L, teacher)).thenReturn(course);
        when(storage.uploadCourseCover(any())).thenReturn(
                new StorageUploadResult("https://example.test/new.jpg", "learne/course-covers/new"));
        when(coverReviews.saveAndFlush(any())).thenThrow(new RuntimeException("DB failed"));
        doThrow(new RuntimeException("queue failed")).when(compensation)
                .enqueueFailedPersistence(13L, "https://example.test/new.jpg", "learne/course-covers/new");

        assertThat(org.assertj.core.api.Assertions.catchRuntimeException(
                () -> service.upload(teacher, 13L,
                        new org.springframework.mock.web.MockMultipartFile("file", "cover.png", "image/png", new byte[8]))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB failed");
    }

    @Test
    void cleanupDecisionRetainsResourceWhenUploadRemovesItsReferenceRace() {
        CourseRepository courses = mock(CourseRepository.class);
        Course course = new Course();
        course.setImageUrl("https://example.test/new.jpg");
        course.setImagePublicId("learne/course-covers/new");
        when(courses.findLockedById(14L)).thenReturn(Optional.of(course));
        CourseCoverCleanupDecisionService decision = new CourseCoverCleanupDecisionService(courses);

        CleanupDecision outcome = decision.evaluate(
                14L,
                "https://example.test/old.jpg",
                "learne/course-covers/old",
                java.util.UUID.randomUUID());

        assertThat(outcome.kind()).isEqualTo(CleanupDecision.Kind.DELETE);

        course.setImageUrl("https://example.test/old.jpg");
        course.setImagePublicId("learne/course-covers/old");
        outcome = decision.evaluate(
                14L,
                "https://example.test/old.jpg",
                "learne/course-covers/old",
                java.util.UUID.randomUUID());
        assertThat(outcome.kind()).isEqualTo(CleanupDecision.Kind.RETAIN);
    }

    private CourseCoverImageService createService(CourseWriteLockService locks, StorageService storage,
                                                  CourseRepository courses, MediaCleanupProducer cleanup) {
        return new CourseCoverImageService(locks, storage, courses, cleanup,
                mock(CourseCoverCompensationService.class), mock(CourseCoverCleanupDecisionService.class),
                mock(CourseCoverReviewRepository.class),
                mock(CoursePendingReviewSnapshotRepository.class),
                mock(CourseRevisionRepository.class), mock(TeacherContentService.class),
                mock(CourseCoverReviewNotificationService.class),
                mock(CatalogInvalidationOutboxService.class), mock(CacheInvalidationService.class));
    }
}
