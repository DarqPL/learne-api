package com.learne.backend.student.learning.dto;

public record PlacementStatusResponse(boolean placementCompleted, Long activeAttemptId, boolean skipped,
        boolean canGenerateRecommendations, String reason) {}
