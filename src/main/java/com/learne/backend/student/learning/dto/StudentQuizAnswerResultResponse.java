package com.learne.backend.student.learning.dto;

import com.learne.backend.student.learning.service.StudentQuestionGradingResult;

public record StudentQuizAnswerResultResponse(
        Long questionId,
        String type,
        String questionText,
        String answerText,
        Long selectedOptionId,
        boolean correct,
        int scorePercent,
        int earnedXp,
        String gradingMethod,
        String feedback,
        String correctAnswer,
        Long correctOptionId,
        String explanation
) {
    public static StudentQuizAnswerResultResponse from(StudentQuestionGradingResult result) {
        Long selectedOptionId = result.selectedOption() == null ? null : result.selectedOption().getId();
        return new StudentQuizAnswerResultResponse(
                result.question().getId(),
                result.question().getType().name(),
                result.question().getQuestionText(),
                result.answer() == null ? null : result.answer().answerText(),
                selectedOptionId,
                result.correct(),
                result.scorePercent(),
                result.earnedXp(),
                result.gradingMethod().name(),
                result.feedback(),
                result.correctAnswer(),
                result.correctOptionId(),
                result.question().getExplanation()
        );
    }
}
