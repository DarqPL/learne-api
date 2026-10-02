package com.learne.backend.student.learning.dto;

import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.enums.CourseAccessStatus;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseType;
import java.time.Instant;
import java.util.List;

public record StudentCourseDetailResponse(
        Long id,
        String name,
        String description,
        CourseType type,
        String level,
        Long teacherId,
        String teacherName,
        String teacherEmail,
        String teacherAvatarUrl,
        Instant publishedAt,
        CoursePricingType pricingType,
        Long priceAmountVnd,
        CourseAccessStatus accessStatus,
        String accessReason,
        boolean enrolled,
        Long activeEnrollmentId,
        List<StudentTopicResponse> topics
) {
    public static StudentCourseDetailResponse from(Course course, CourseEnrollment enrollment,
                                                    CourseAccessStatus accessStatus, String accessReason,
                                                    boolean includeContent) {
        return from(SharedStudentCourseDetailResponse.from(course), accessStatus, accessReason, enrollment);
    }

    public static StudentCourseDetailResponse from(SharedStudentCourseDetailResponse shared,
                                                   CourseAccessStatus accessStatus, String accessReason,
                                                   CourseEnrollment enrollment) {
        return new StudentCourseDetailResponse(
                shared.id(),
                shared.name(),
                shared.description(),
                shared.type(),
                shared.level(),
                shared.teacherId(),
                shared.teacherName(),
                shared.teacherEmail(),
                shared.teacherAvatarUrl(),
                shared.publishedAt(),
                shared.pricingType(),
                shared.priceAmountVnd(),
                accessStatus,
                accessReason,
                enrollment != null,
                enrollment == null ? null : enrollment.getId(),
                accessStatus == CourseAccessStatus.LOCKED ? List.of() : List.copyOf(shared.topics())
        );
    }
}
