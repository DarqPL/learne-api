package com.learne.backend.student.learning.dto;

import java.util.List;

public record SubmitPlacementResponse(
        Long attemptId,
        String status,
        int scorePercent,
        List<PlacementSkillStatResponse> skillStats
) {}
