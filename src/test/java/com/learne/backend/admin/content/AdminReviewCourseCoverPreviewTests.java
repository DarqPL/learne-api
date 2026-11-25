package com.learne.backend.admin.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.admin.content.dto.AdminReviewCourseDetailResponse;
import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import org.junit.jupiter.api.Test;

class AdminReviewCourseCoverPreviewTests {
    @Test
    void revisionDetailDistinguishesCurrentAndProposedCover() {
        User teacher = new User();
        Course course = new Course();
        course.setTeacher(teacher);
        course.setImageUrl("https://example.test/current.jpg");
        CourseRevision revision = new CourseRevision();
        revision.setCourse(course);
        revision.setTeacher(teacher);
        revision.setStatus(CourseRevisionStatus.PENDING_REVIEW);
        revision.setCoverImageUrl("https://example.test/proposed.jpg");

        var response = new ObjectMapper().valueToTree(
                AdminReviewCourseDetailResponse.from(revision));

        assertThat(response.hasNonNull("currentCoverImageUrl")).isTrue();
        assertThat(response.get("currentCoverImageUrl").asText())
                .isEqualTo("https://example.test/current.jpg");
        assertThat(response.hasNonNull("proposedCoverImageUrl")).isTrue();
        assertThat(response.get("proposedCoverImageUrl").asText())
                .isEqualTo("https://example.test/proposed.jpg");
    }
}
