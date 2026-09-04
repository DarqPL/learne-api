package com.learne.backend.student.learning.ai;

public interface AiLearningPathClient {
    AiLearningPathResponse generateLearningPath(AiLearningPathRequest request);

    AiCourseRecommendationResponse generateCourseRecommendations(AiCourseRecommendationRequest request);
}
