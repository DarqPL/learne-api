package com.learne.backend.student.learning.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.entity.Subscription;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.monetization.repository.CourseEntitlementRepository;
import com.learne.backend.monetization.repository.SubscriptionRepository;
import com.learne.backend.monetization.repository.VipCourseSelectionRepository;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class CourseAccessContextService {
    private final CourseEnrollmentRepository courseEnrollmentRepository;
    private final CourseEntitlementRepository courseEntitlementRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final VipCourseSelectionRepository vipCourseSelectionRepository;

    public CourseAccessContextService(CourseEnrollmentRepository courseEnrollmentRepository,
                                      CourseEntitlementRepository courseEntitlementRepository,
                                      SubscriptionRepository subscriptionRepository,
                                      VipCourseSelectionRepository vipCourseSelectionRepository) {
        this.courseEnrollmentRepository = courseEnrollmentRepository;
        this.courseEntitlementRepository = courseEntitlementRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.vipCourseSelectionRepository = vipCourseSelectionRepository;
    }

    public CourseAccessContext build(User student, Collection<Long> courseIds) {
        if (courseIds.isEmpty()) {
            return new CourseAccessContext(Set.of(), Set.of(), Set.of(), false);
        }
        Instant now = Instant.now();
        List<Subscription> activeSubscriptions = subscriptionRepository.findActiveByUser(student, now);
        boolean hasProAccess = activeSubscriptions.stream()
                .anyMatch(subscription -> subscription.getPlanCode() == PlanCode.PRO || subscription.getPlanCode() == PlanCode.PRO_PLUS);
        List<Subscription> vipSubscriptions = activeSubscriptions.stream()
                .filter(subscription -> subscription.getPlanCode() == PlanCode.VIP)
                .toList();
        Set<Long> vipCourseIds = vipSubscriptions.isEmpty()
                ? Set.of()
                : new HashSet<>(vipCourseSelectionRepository.findActiveSelectedCourseIds(vipSubscriptions, courseIds));

        return new CourseAccessContext(
                new HashSet<>(courseEnrollmentRepository.findEnrolledCourseIds(student, courseIds)),
                new HashSet<>(courseEntitlementRepository.findActiveCourseIds(student, courseIds, now)),
                vipCourseIds,
                hasProAccess);
    }
}
