package com.learne.backend.teacher.content.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.enrichment.CourseReviewEnrichmentService;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseSubmitPreparationService {
    private final CourseRevisionRepository courseRevisionRepository;
    private final CourseReviewEnrichmentService enrichmentService;

    public CourseSubmitPreparationService(
            CourseRevisionRepository courseRevisionRepository,
            CourseReviewEnrichmentService enrichmentService) {
        this.courseRevisionRepository = courseRevisionRepository;
        this.enrichmentService = enrichmentService;
    }

    @Transactional(readOnly = true)
    public CourseReviewEnrichmentService.CourseRevisionSnapshot prepare(User teacher, Long courseId) {
        CourseRevision revision = courseRevisionRepository.findTopByCourseIdOrderByRevisionNumberDesc(courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course revision not found."));
        if (revision.getTeacher() == null || revision.getTeacher().getId() == null
                || !revision.getTeacher().getId().equals(teacher.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Course not found.");
        }
        if (revision.getStatus() != CourseRevisionStatus.DRAFT
                && revision.getStatus() != CourseRevisionStatus.REJECTED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only draft or rejected content can be submitted for review.");
        }
        if (revision.getTopics().stream()
                .noneMatch(topic -> topic.getLessons().stream().anyMatch(lesson -> !lesson.getQuestions().isEmpty()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Course must have at least one topic, lesson, and question before review.");
        }
        return enrichmentService.buildSnapshot(revision);
    }
}
