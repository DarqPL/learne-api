package com.learne.backend.admin.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learne.backend.admin.content.service.AdminCourseCoverReviewService;
import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.cache.CatalogInvalidationOutboxService;
import com.learne.backend.domain.auth.User;
import com.learne.backend.notification.CourseCoverReviewNotificationService;
import com.learne.backend.shared.api.ApiException;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminCourseCoverReviewServiceTests {
    @Mock CourseCoverReviewRepository reviews;
    @Mock CoursePendingReviewSnapshotRepository snapshots;
    @Mock CourseRepository courses;
    @Mock CourseWriteLockService locks;
    @Mock MediaCleanupProducer cleanup;
    @Mock CourseCoverReviewNotificationService notifications;
    @Mock CatalogInvalidationOutboxService outbox;
    @Mock CacheInvalidationService caches;
    @Mock JdbcTemplate jdbc;
    @Mock CursorTokenService cursors;

    private final User teacher = user(7L);
    private final User admin = user(8L);
    private Course course;
    private CourseCoverReview request;
    private CoursePendingReviewSnapshot snapshot;
    private AdminCourseCoverReviewService service;

    @BeforeEach
    void setUp() {
        course = new Course();
        ReflectionTestUtils.setField(course, "id", 10L);
        course.setTeacher(teacher);
        course.setImageUrl("https://example.test/current.jpg");
        course.setImagePublicId("current-cover");
        request = new CourseCoverReview();
        ReflectionTestUtils.setField(request, "id", 20L);
        request.setCourse(course);
        request.setTeacher(teacher);
        request.setImageUrl("https://example.test/proposed.jpg");
        request.setImagePublicId("proposed-cover");
        request.setStatus(CourseCoverReviewStatus.PENDING_REVIEW);
        snapshot = new CoursePendingReviewSnapshot();
        snapshot.setCourse(course);
        snapshot.setSnapshotKind(CoursePendingReviewSnapshotKind.COVER_ONLY);
        snapshot.setCoverReview(request);
        service = new AdminCourseCoverReviewService(
                jdbc, cursors, locks, reviews, snapshots, courses, cleanup, notifications,
                outbox, caches);
    }

    @Test
    void approvalPromotesOnlyTheRegistryCurrentProposalAndRecordsDurableInvalidation() {
        when(reviews.findCourseIdById(20L)).thenReturn(Optional.of(10L));
        when(locks.adminCourse(10L)).thenReturn(course);
        when(snapshots.findLockedByCourseId(10L)).thenReturn(Optional.of(snapshot));
        when(courses.saveAndFlush(course)).thenReturn(course);

        service.approve(admin, 20L);

        assertThat(course.getImageUrl()).isEqualTo("https://example.test/proposed.jpg");
        assertThat(course.getImagePublicId()).isEqualTo("proposed-cover");
        assertThat(request.getStatus()).isEqualTo(CourseCoverReviewStatus.APPROVED);
        verify(snapshots).delete(snapshot);
        verify(outbox).recordPublicCoverChange(10L);
        verify(cleanup).enqueueCourseCover(any(), eq(10L),
                eq("https://example.test/current.jpg"), eq("current-cover"));
        verify(notifications).coverDecision(request, CourseCoverReviewStatus.APPROVED, null);
    }

    @Test
    void staleProposalCannotBeApprovedOrChangeThePublicCover() {
        when(reviews.findCourseIdById(20L)).thenReturn(Optional.of(10L));
        when(locks.adminCourse(10L)).thenReturn(course);
        CourseCoverReview replacement = new CourseCoverReview();
        ReflectionTestUtils.setField(replacement, "id", 21L);
        CoursePendingReviewSnapshot newer = new CoursePendingReviewSnapshot();
        newer.setCourse(course);
        newer.setSnapshotKind(CoursePendingReviewSnapshotKind.COVER_ONLY);
        newer.setCoverReview(replacement);
        when(snapshots.findLockedByCourseId(10L)).thenReturn(Optional.of(newer));

        assertThatThrownBy(() -> service.approve(admin, 20L)).isInstanceOf(ApiException.class);

        assertThat(course.getImageUrl()).isEqualTo("https://example.test/current.jpg");
        verify(courses, never()).saveAndFlush(any());
        verify(notifications, never()).coverDecision(any(), any(), any());
    }

    @Test
    void rejectionPreservesPublicCoverAndQueuesProposalForCleanup() {
        when(reviews.findCourseIdById(20L)).thenReturn(Optional.of(10L));
        when(locks.adminCourse(10L)).thenReturn(course);
        when(snapshots.findLockedByCourseId(10L)).thenReturn(Optional.of(snapshot));

        service.reject(admin, 20L, "Image does not match the course.");

        assertThat(course.getImageUrl()).isEqualTo("https://example.test/current.jpg");
        assertThat(course.getImagePublicId()).isEqualTo("current-cover");
        assertThat(request.getStatus()).isEqualTo(CourseCoverReviewStatus.REJECTED);
        assertThat(request.getRejectionReason()).isEqualTo("Image does not match the course.");
        assertThat(request.getImageUrl()).isNull();
        assertThat(request.getImagePublicId()).isNull();
        verify(snapshots).delete(snapshot);
        verify(cleanup).enqueueCourseCover(any(), eq(10L),
                eq("https://example.test/proposed.jpg"), eq("proposed-cover"));
        verify(notifications).coverDecision(
                request, CourseCoverReviewStatus.REJECTED, "Image does not match the course.");
        verify(outbox, never()).recordPublicCoverChange(anyLong());
    }

    private static User user(Long id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
