package com.learne.backend.student.learning.service;

import com.learne.backend.domain.auth.AccountOrigin;
import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.enums.CourseAccessStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import java.util.Set;

public record CourseAccessContext(Set<Long> enrolledCourseIds,
                                  Set<Long> entitledCourseIds,
                                  Set<Long> vipCourseIds,
                                  boolean hasProAccess) {

    public CourseAccessStatus accessStatus(User student,
                                           Long courseId,
                                           String level,
                                           CoursePricingType pricingType,
                                           Long teacherId) {
        if ("A1".equalsIgnoreCase(level) || pricingType == null || pricingType == CoursePricingType.FREE) {
            return CourseAccessStatus.FREE;
        }
        if (entitledCourseIds.contains(courseId) || hasProAccess || vipCourseIds.contains(courseId)) {
            return CourseAccessStatus.OWNED;
        }
        if (student != null
                && student.getAccountOrigin() == AccountOrigin.TEACHER_CREATED
                && student.getOwnerTeacher() != null
                && student.getOwnerTeacher().getId().equals(teacherId)) {
            return CourseAccessStatus.OWNED;
        }
        return CourseAccessStatus.LOCKED;
    }
}
