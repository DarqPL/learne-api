package com.learne.backend.student.learning.dto;

import java.time.Instant;
import java.util.List;

public record PlacementAttemptResponse(Long id, String status, Instant startedAt, Instant expiresAt,
        long remainingSeconds, int durationSeconds, List<PlacementQuestionResponse> questions) {}
