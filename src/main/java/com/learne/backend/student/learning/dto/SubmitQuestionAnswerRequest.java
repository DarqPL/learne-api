package com.learne.backend.student.learning.dto;

import jakarta.validation.constraints.NotNull;

public record SubmitQuestionAnswerRequest(
        @NotNull Long questionId,
        Long selectedOptionId,
        String answerText
) {
}
