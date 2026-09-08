package com.learne.backend.ai.tutor.dto;

import com.learne.backend.ai.tutor.enums.AiTutorPracticeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAiConversationRequest(
        @NotBlank @Size(max = 120) String title,
        @NotNull AiTutorPracticeType practiceType) {
}
