package com.learne.backend.ai.tutor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendAiTutorMessageRequest(@NotBlank @Size(max = 2000) String message) {
}
