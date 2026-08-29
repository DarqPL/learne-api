package com.learne.backend.student.learning.dto;

import com.learne.backend.teacher.content.enums.CourseType;
import java.time.Instant;

public record StudentCourseSummaryResponse(
        Long id,
        String name,
        String description,
        CourseType type,
        String level,
        Long teacherId,
        String teacherName,
        String teacherEmail,
        String teacherAvatarUrl,
        long topicCount,
        long lessonCount,
        long questionCount,
        Instant publishedAt,
        boolean enrolled
) {
}
