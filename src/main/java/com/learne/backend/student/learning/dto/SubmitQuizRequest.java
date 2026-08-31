package com.learne.backend.student.learning.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SubmitQuizRequest(
        @NotEmpty List<@NotNull @Valid SubmitQuestionAnswerRequest> answers
) {
}
