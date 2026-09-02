package com.learne.backend.student.learning.dto;

import java.math.BigDecimal;

public record StudentLearningPathItemResponse(
        Long id,
        Long enrollmentId,
        Long lessonId,
        String lessonTitle,
        Long courseId,
        String courseTitle,
        Long teacherId,
        String teacherName,
        int sequenceOrder,
        String reason,
        BigDecimal score,
        boolean completed) {
}
