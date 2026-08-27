package com.learne.backend.admin.content.service;

import com.learne.backend.admin.content.dto.AdminReviewCourseDetailResponse;
import com.learne.backend.admin.content.dto.AdminReviewCourseSummaryResponse;
import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminContentReviewService {
    private static final String REVIEW_STATUS_MESSAGE = "Only pending review courses can be approved or rejected.";

    private final CourseRepository courseRepository;

    public AdminContentReviewService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    @Transactional(readOnly = true)
    public List<AdminReviewCourseSummaryResponse> listPendingCourses() {
        return courseRepository.findByStatusOrderByUpdatedAtAscIdAsc(ContentStatus.PENDING_REVIEW).stream()
                .map(AdminReviewCourseSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminReviewCourseDetailResponse getCourse(Long courseId) {
        return AdminReviewCourseDetailResponse.from(findReviewCourse(courseId));
    }

    @Transactional
    public AdminReviewCourseDetailResponse approveCourse(User admin, Long courseId) {
        Course course = findReviewCourse(courseId);
        ensurePendingReview(course);
        if (!hasMinimumReviewContent(course)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Course must have at least one topic, lesson, and question before approval.");
        }

        Instant now = Instant.now();
        course.setStatus(ContentStatus.APPROVED);
        course.setReviewedBy(admin);
        course.setReviewedAt(now);
        course.setPublishedAt(now);
        course.setRejectionReason(null);

        course.getTopics().forEach(topic -> topic.getLessons().forEach(lesson -> {
            lesson.setStatus(ContentStatus.APPROVED);
            lesson.setReviewedBy(admin);
            lesson.setReviewedAt(now);
            lesson.setPublishedAt(now);
            lesson.setRejectionReason(null);

            lesson.getQuestions().forEach(question -> {
                question.setStatus(ContentStatus.APPROVED);
                question.setReviewedBy(admin);
                question.setReviewedAt(now);
                question.setRejectionReason(null);
            });
        }));

        return AdminReviewCourseDetailResponse.from(courseRepository.save(course));
    }

    @Transactional
    public AdminReviewCourseDetailResponse rejectCourse(User admin, Long courseId, String reason) {
        Course course = findReviewCourse(courseId);
        ensurePendingReview(course);

        Instant now = Instant.now();
        course.setStatus(ContentStatus.REJECTED);
        course.setReviewedBy(admin);
        course.setReviewedAt(now);
        course.setPublishedAt(null);
        course.setRejectionReason(reason);

        course.getTopics().forEach(topic -> topic.getLessons().forEach(lesson -> {
            lesson.setStatus(ContentStatus.REJECTED);
            lesson.setReviewedBy(admin);
            lesson.setReviewedAt(now);
            lesson.setPublishedAt(null);
            lesson.setRejectionReason(null);

            lesson.getQuestions().forEach(question -> {
                question.setStatus(ContentStatus.REJECTED);
                question.setReviewedBy(admin);
                question.setReviewedAt(now);
                question.setRejectionReason(null);
            });
        }));

        return AdminReviewCourseDetailResponse.from(courseRepository.save(course));
    }

    private Course findReviewCourse(Long courseId) {
        return courseRepository.findReviewDetailById(courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
    }

    private void ensurePendingReview(Course course) {
        if (course.getStatus() != ContentStatus.PENDING_REVIEW) {
            throw new ApiException(HttpStatus.BAD_REQUEST, REVIEW_STATUS_MESSAGE);
        }
    }

    private boolean hasMinimumReviewContent(Course course) {
        return course.getTopics().stream()
                .anyMatch(topic -> topic.getLessons().stream()
                        .anyMatch(lesson -> !lesson.getQuestions().isEmpty()));
    }
}
