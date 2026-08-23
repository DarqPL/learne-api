package com.learne.backend.teacher.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QuestionOptionRequest(
        @NotBlank @Size(max = 1024) String optionText,
        boolean correct,
        Integer orderIndex
) {
    public QuestionOptionRequest {
        if (optionText != null) optionText = optionText.trim();
    }
}
