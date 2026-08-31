package com.learne.backend.student.learning.dto;

import java.util.List;

public record StudentQuizAttemptResponse(
        Long attemptId,
        Long enrollmentId,
        Long lessonId,
        int scorePercent,
        boolean passed,
        int earnedXp,
        StudentQuizProgressResponse progress,
        StudentStatsResponse stats,
        List<StudentQuizAnswerResultResponse> answers
) {
}
