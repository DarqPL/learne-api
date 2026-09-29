package com.learne.backend.monetization.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.AccountOrigin;
import com.learne.backend.monetization.entity.Subscription;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.monetization.enums.SubscriptionStatus;
import com.learne.backend.monetization.repository.CourseEntitlementRepository;
import com.learne.backend.monetization.repository.SubscriptionRepository;
import com.learne.backend.monetization.repository.VipCourseSelectionRepository;
import com.learne.backend.student.learning.enums.CourseAccessStatus;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class CourseAccessService {
    private final CourseEntitlementRepository courseEntitlementRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final VipCourseSelectionRepository vipCourseSelectionRepository;

    public CourseAccessService(CourseEntitlementRepository courseEntitlementRepository,
                               SubscriptionRepository subscriptionRepository,
                               VipCourseSelectionRepository vipCourseSelectionRepository) {
        this.courseEntitlementRepository = courseEntitlementRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.vipCourseSelectionRepository = vipCourseSelectionRepository;
    }

    public CourseAccessStatus accessStatus(User student, Course course) {
        if (course == null
                || "A1".equalsIgnoreCase(course.getLevel())
                || course.getPricingType() == null
                || course.getPricingType() == CoursePricingType.FREE) {
            return CourseAccessStatus.FREE;
        }
        if (student != null && courseEntitlementRepository.existsActiveCourseAccess(student, course, Instant.now())) {
            return CourseAccessStatus.OWNED;
        }
        if (student != null && hasPlanAccess(student, course)) {
            return CourseAccessStatus.OWNED;
        }
        if (student != null
                && student.getAccountOrigin() == AccountOrigin.TEACHER_CREATED
                && student.getOwnerTeacher() != null
                && course.getTeacher() != null
                && student.getOwnerTeacher().getId().equals(course.getTeacher().getId())) {
            return CourseAccessStatus.OWNED;
        }
        return CourseAccessStatus.LOCKED;
    }

    public boolean canEnroll(User student, Course course) {
        return accessStatus(student, course) != CourseAccessStatus.LOCKED;
    }

    private boolean hasPlanAccess(User student, Course course) {
        Instant now = Instant.now();
        if (subscriptionRepository.existsActivePlan(student, PlanCode.PRO, now)
                || subscriptionRepository.existsActivePlan(student, PlanCode.PRO_PLUS, now)) {
            return true;
        }
        return subscriptionRepository.findByUserAndStatusAndEndsAtAfter(student, SubscriptionStatus.ACTIVE, now)
                .stream()
                .filter(subscription -> subscription.getPlanCode() == PlanCode.VIP)
                .anyMatch(subscription -> vipCourseSelectionRepository.existsActiveSelection(student, course, subscription));
    }
}
