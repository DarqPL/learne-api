package com.learne.backend.monetization.service;

import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.dto.VipCourseSelectionResponse;
import com.learne.backend.monetization.entity.Subscription;
import com.learne.backend.monetization.entity.VipCourseSelection;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.monetization.enums.SubscriptionStatus;
import com.learne.backend.monetization.repository.SubscriptionRepository;
import com.learne.backend.monetization.repository.VipCourseSelectionRepository;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VipSelectionService {
    private final SubscriptionRepository subscriptionRepository;
    private final VipCourseSelectionRepository vipCourseSelectionRepository;
    private final CourseRepository courseRepository;
    private final CacheInvalidationService cacheInvalidationService;

    public VipSelectionService(SubscriptionRepository subscriptionRepository,
                                VipCourseSelectionRepository vipCourseSelectionRepository,
                                CourseRepository courseRepository,
                                CacheInvalidationService cacheInvalidationService) {
        this.subscriptionRepository = subscriptionRepository;
        this.vipCourseSelectionRepository = vipCourseSelectionRepository;
        this.courseRepository = courseRepository;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    @Transactional(readOnly = true)
    public List<VipCourseSelectionResponse> listActiveSelections(User student) {
        Subscription subscription = activeVipSubscription(student);
        if (subscription == null) {
            return List.of();
        }
        return vipCourseSelectionRepository.findBySubscriptionAndReplacedAtIsNullOrderBySelectedAtAsc(subscription)
                .stream()
                .map(VipCourseSelectionResponse::from)
                .toList();
    }

    @Transactional
    public VipCourseSelectionResponse select(User student, Long courseId) {
        Subscription subscription = activeVipSubscription(student);
        if (subscription == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Active VIP subscription is required.");
        }
        subscription = subscriptionRepository.findByIdForUpdate(subscription.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Active VIP subscription is required."));

        if (courseId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "courseId is required.");
        }

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Course is not eligible for VIP selection."));
        validateCourse(course);

        List<VipCourseSelection> activeSelections = vipCourseSelectionRepository
                .findBySubscriptionAndReplacedAtIsNullOrderBySelectedAtAsc(subscription);
        for (VipCourseSelection selection : activeSelections) {
            if (selection.getCourse().getId().equals(course.getId())) {
                return VipCourseSelectionResponse.from(selection);
            }
        }

        String level = course.getLevel().toUpperCase();
        long selectedAtLevel = activeSelections.stream()
                .filter(selection -> level.equalsIgnoreCase(selection.getLevel()))
                .count();
        if (selectedAtLevel >= quota(level)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VIP quota exceeded for " + level + ".");
        }

        VipCourseSelection selection = new VipCourseSelection();
        selection.setSubscription(subscription);
        selection.setUser(student);
        selection.setCourse(course);
        selection.setLevel(level);
        VipCourseSelectionResponse response = VipCourseSelectionResponse.from(vipCourseSelectionRepository.save(selection));
        cacheInvalidationService.evictAccessForStudent(student.getId());
        cacheInvalidationService.evictStudentLearning(student.getId());
        cacheInvalidationService.evictAdminDashboard();
        return response;
    }

    private Subscription activeVipSubscription(User student) {
        Instant now = Instant.now();
        return subscriptionRepository.findByUserAndStatusAndEndsAtAfter(student, SubscriptionStatus.ACTIVE, now)
                .stream()
                .filter(subscription -> subscription.getPlanCode() == PlanCode.VIP)
                .max(Comparator.comparing(Subscription::getEndsAt))
                .orElse(null);
    }

    private void validateCourse(Course course) {
        if (course.getStatus() != ContentStatus.APPROVED
                || course.getPricingType() != CoursePricingType.PAID
                || "A1".equalsIgnoreCase(course.getLevel())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Course is not eligible for VIP selection.");
        }
        quota(course.getLevel().toUpperCase());
    }

    private int quota(String level) {
        return switch (level) {
            case "A2" -> 2;
            case "B1", "B2", "C1", "C2" -> 1;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported VIP course level.");
        };
    }
}
