package com.learne.backend.student.learning.dto;

import java.math.BigDecimal;

public record CourseRecommendationItemResponse(Long id, Long courseId, String courseTitle,
        String description, String type, String level, Long teacherId, String teacherName,
        boolean enrolled, String enrollmentStatus, int sequenceOrder, String reason, BigDecimal score) {}
