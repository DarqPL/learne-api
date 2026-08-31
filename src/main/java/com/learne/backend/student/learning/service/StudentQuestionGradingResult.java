package com.learne.backend.student.learning.service;

import com.learne.backend.student.learning.dto.SubmitQuestionAnswerRequest;
import com.learne.backend.student.learning.enums.GradingMethod;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;

public record StudentQuestionGradingResult(
        Question question,
        SubmitQuestionAnswerRequest answer,
        QuestionOption selectedOption,
        boolean correct,
        int scorePercent,
        int earnedXp,
        GradingMethod gradingMethod,
        String feedback,
        String correctAnswer,
        Long correctOptionId,
        boolean gradable
) {
}
