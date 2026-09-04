package com.learne.backend.student.learning.ai;

import java.math.BigDecimal;
import java.util.List;

public record AiCourseRecommendationResponse(
        List<String> weaknesses,
        List<RecommendationPayload> recommendations) {
    public record RecommendationPayload(Long courseId, BigDecimal score, String reason) {}
}
