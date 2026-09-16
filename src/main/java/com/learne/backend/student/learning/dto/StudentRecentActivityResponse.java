package com.learne.backend.student.learning.dto;

import java.time.Instant;

public record StudentRecentActivityResponse(
        Long id,
        String activityType,
        String title,
        Instant happenedAt
) {
}
