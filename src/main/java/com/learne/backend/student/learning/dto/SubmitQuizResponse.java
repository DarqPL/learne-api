package com.learne.backend.student.learning.dto;

import java.util.List;

public record SubmitQuizResponse(
        Long attemptId,
        Long enrollmentId,
        Long lessonId,
        int scorePercent,
        boolean passed,
        int earnedXp,
        boolean xpAwarded,
        StudentQuizProgressResponse progress,
        StudentStatsResponse stats,
        List<StudentQuizAnswerResultResponse> answers
) {
}
