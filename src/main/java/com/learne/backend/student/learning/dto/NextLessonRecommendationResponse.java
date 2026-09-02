package com.learne.backend.student.learning.dto;

import java.math.BigDecimal;

public record NextLessonRecommendationResponse(
        Long enrollmentId,
        Long lessonId,
        String lessonTitle,
        Long courseId,
        String courseTitle,
        Long teacherId,
        String teacherName,
        String reason,
        BigDecimal score) {
}
