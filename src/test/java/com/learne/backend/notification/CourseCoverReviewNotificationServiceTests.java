package com.learne.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseCoverReview;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.enums.CourseCoverReviewStatus;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CourseCoverReviewNotificationServiceTests {
    @Mock
    private NotificationRepository notifications;

    @Test
    void rejectedStandaloneCoverNotificationIncludesCourseAndReason() {
        User teacher = user(7L);
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", 10L);
        course.setName("Grammar Course");
        course.setTeacher(teacher);
        CourseCoverReview review = new CourseCoverReview();
        ReflectionTestUtils.setField(review, "id", 20L);
        review.setCourse(course);
        review.setTeacher(teacher);
        CourseCoverReviewNotificationService service =
                new CourseCoverReviewNotificationService(notifications);

        service.coverDecision(review, CourseCoverReviewStatus.REJECTED, "Low resolution.");

        ArgumentCaptor<NotificationRepository.Intent> intent =
                ArgumentCaptor.forClass(NotificationRepository.Intent.class);
        verify(notifications).insertIfAbsent(intent.capture());
        assertThat(intent.getValue().type()).isEqualTo("COURSE_COVER_REVIEW_REJECTED");
        assertThat(intent.getValue().message())
                .contains("Grammar Course", "rejected", "Low resolution.");
        assertThat(intent.getValue().courseId()).isEqualTo(10L);
        assertThat(intent.getValue().userId()).isEqualTo(7L);
    }

    @Test
    void rejectedRevisionCoverNotificationIncludesReasonAndRevisionIdentity() {
        User teacher = user(7L);
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", 10L);
        CourseRevision revision = new CourseRevision();
        ReflectionTestUtils.setField(revision, "id", 30L);
        revision.setCourse(course);
        revision.setTeacher(teacher);
        revision.setName("Grammar Course");
        revision.setCoverImageUrl("https://example.test/proposed.jpg");
        revision.setStatus(CourseRevisionStatus.REJECTED);
        revision.setRejectionReason("Needs a different image.");
        CourseCoverReviewNotificationService service =
                new CourseCoverReviewNotificationService(notifications);

        service.revisionDecision(revision);

        ArgumentCaptor<NotificationRepository.Intent> intent =
                ArgumentCaptor.forClass(NotificationRepository.Intent.class);
        verify(notifications).insertIfAbsent(intent.capture());
        assertThat(intent.getValue().type()).isEqualTo("COURSE_REVISION_COVER_REJECTED");
        assertThat(intent.getValue().message())
                .contains("Grammar Course", "rejected", "Needs a different image.");
        assertThat(intent.getValue().revisionId()).isEqualTo(30L);
    }

    private static User user(Long id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
