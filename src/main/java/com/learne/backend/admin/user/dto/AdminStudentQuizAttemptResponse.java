package com.learne.backend.admin.user.dto;

import java.time.Instant;

public record AdminStudentQuizAttemptResponse(
        Long attemptId,
        String lessonTitle,
        int scorePercent,
        boolean passed,
        Instant submittedAt) {
}
