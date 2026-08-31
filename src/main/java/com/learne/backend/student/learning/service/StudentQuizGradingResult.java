package com.learne.backend.student.learning.service;

import java.util.List;

public record StudentQuizGradingResult(
        int scorePercent,
        boolean passed,
        int earnedXp,
        List<StudentQuestionGradingResult> answers
) {
}
