package com.learne.backend.student.learning.dto;

import java.time.Instant;
import java.util.List;

public record CourseRecommendationPathResponse(Long id, String status, Instant generatedAt,
        String source, List<String> weaknesses, List<CourseRecommendationItemResponse> items) {}
