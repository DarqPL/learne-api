package com.learne.backend.ai.tutor.dto;

public record AiFeedbackResponse(
        Long id,
        String errorType,
        String originalText,
        String correctedText,
        String explanation) {
}
