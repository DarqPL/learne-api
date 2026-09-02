package com.learne.backend.student.learning.ai;

import java.math.BigDecimal;
import java.util.List;

public record AiLearningPathResponse(
        List<String> weaknesses,
        List<RecommendationPayload> recommendations) {
    public record RecommendationPayload(Long lessonId, BigDecimal score, String reason) {}
}
