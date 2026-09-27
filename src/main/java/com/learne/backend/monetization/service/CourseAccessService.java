package com.learne.backend.monetization.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.AccountOrigin;
import com.learne.backend.monetization.repository.CourseEntitlementRepository;
import com.learne.backend.student.learning.enums.CourseAccessStatus;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class CourseAccessService {
    private final CourseEntitlementRepository courseEntitlementRepository;

    public CourseAccessService(CourseEntitlementRepository courseEntitlementRepository) {
        this.courseEntitlementRepository = courseEntitlementRepository;
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
}
