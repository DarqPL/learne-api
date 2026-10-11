package com.learne.backend.teacher.content.service;

import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.dto.CourseResponse;
import com.learne.backend.teacher.content.enrichment.CourseReviewEnrichmentService;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseSubmitFinalizationService {
    private final CourseRepository courseRepository;
    private final CourseRevisionRepository courseRevisionRepository;
    private final CacheInvalidationService cacheInvalidationService;
    private final CourseReviewEnrichmentService enrichmentService;

    public CourseSubmitFinalizationService(
            CourseRepository courseRepository,
            CourseRevisionRepository courseRevisionRepository,
            CacheInvalidationService cacheInvalidationService,
            CourseReviewEnrichmentService enrichmentService) {
        this.courseRepository = courseRepository;
        this.courseRevisionRepository = courseRevisionRepository;
        this.cacheInvalidationService = cacheInvalidationService;
        this.enrichmentService = enrichmentService;
    }

    @Transactional
    public CourseResponse finalizeSubmit(
            User teacher, Long courseId,
            CourseReviewEnrichmentService.EnrichedCourseRevisionSnapshot enrichedSnapshot) {
        Course course = courseRepository.findByIdAndTeacher(courseId, teacher)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
        CourseRevision latestRevision = courseRevisionRepository.findLockedLatestByCourseId(courseId, PageRequest.of(0, 1)).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course revision not found."));
        if (!latestRevision.getTeacher().getId().equals(teacher.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Course not found.");
        }
        if (latestRevision.getStatus() != CourseRevisionStatus.DRAFT
                && latestRevision.getStatus() != CourseRevisionStatus.REJECTED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only draft or rejected content can be submitted for review.");
        }
        enrichmentService.applyEnrichment(enrichedSnapshot, latestRevision);
        courseRevisionRepository.findByCourseAndStatus(course, CourseRevisionStatus.PENDING_REVIEW).stream()
                .filter(revision -> !revision.getId().equals(latestRevision.getId()))
                .forEach(revision -> revision.setStatus(CourseRevisionStatus.SUPERSEDED));
        latestRevision.setStatus(CourseRevisionStatus.PENDING_REVIEW);
        latestRevision.setSubmittedAt(Instant.now());
        latestRevision.setRejectionReason(null);
        if (course.getPublishedAt() == null) {
            course.setStatus(ContentStatus.PENDING_REVIEW);
            course.setName(latestRevision.getName());
            course.setDescription(latestRevision.getDescription());
            course.setType(latestRevision.getType());
            course.setLevel(latestRevision.getLevel());
            course.setOrderIndex(latestRevision.getOrderIndex());
            course.setRejectionReason(null);
        }
        CourseResponse response = CourseResponse.from(courseRepository.save(course), latestRevision);
        cacheInvalidationService.evictTeacherDashboard(teacher.getId());
        cacheInvalidationService.evictGlobalCourseCatalog();
        cacheInvalidationService.evictAdminDashboard();
        return response;
    }
}
