package com.learne.backend.student.learning.dto;

import com.learne.backend.student.learning.service.StudentQuestionGradingResult;
import com.learne.backend.teacher.content.entity.QuestionOption;

public record StudentQuizAnswerResultResponse(
        Long questionId,
        String type,
        String questionText,
        String answerText,
        Long selectedOptionId,
        String selectedOptionText,
        boolean correct,
        int scorePercent,
        int earnedXp,
        String gradingMethod,
        String feedback,
        String correctAnswer,
        Long correctOptionId,
        String correctOptionText,
        String explanation
) {
    public static StudentQuizAnswerResultResponse from(StudentQuestionGradingResult result) {
        Long selectedOptionId = result.selectedOption() == null ? null : result.selectedOption().getId();
        String selectedOptionText = result.selectedOption() == null ? null : result.selectedOption().getOptionText();
        String correctOptionText = result.correctOptionId() == null ? null : result.question().getOptions().stream()
                .filter(option -> option.getId().equals(result.correctOptionId()))
                .findFirst()
                .map(QuestionOption::getOptionText)
                .orElse(null);
        return new StudentQuizAnswerResultResponse(
                result.question().getId(),
                result.question().getType().name(),
                result.question().getQuestionText(),
                result.answer() == null ? null : result.answer().answerText(),
                selectedOptionId,
                selectedOptionText,
                result.correct(),
                result.scorePercent(),
                result.earnedXp(),
                result.gradingMethod().name(),
                result.feedback(),
                result.correctAnswer(),
                result.correctOptionId(),
                correctOptionText,
                result.question().getExplanation()
        );
    }
}
