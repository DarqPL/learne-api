package com.learne.backend.teacher.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record QuestionOptionRequest(
        @NotBlank @Size(max = 1024) String optionText,
        boolean correct,
        Integer orderIndex
) {
    QuestionOptionRequest {
        if (optionText != null) optionText = optionText.trim();
    }
}
