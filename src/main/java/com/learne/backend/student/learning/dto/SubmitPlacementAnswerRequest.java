package com.learne.backend.student.learning.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SubmitPlacementAnswerRequest(
        @NotNull Long questionId,
        @Size(max = 4096) String answerText,
        Long selectedOptionId,
        List<Long> selectedOptionIds
) {}
