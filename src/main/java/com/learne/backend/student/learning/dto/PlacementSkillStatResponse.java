package com.learne.backend.student.learning.dto;

import java.util.List;

public record PlacementSkillStatResponse(
        String skill,
        int correctCount,
        int totalCount,
        int scorePercent,
        List<String> weakKnowledgePoints
) {}
