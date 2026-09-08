package com.learne.backend.ai.tutor.dto;

import com.learne.backend.ai.tutor.enums.AiMessageSenderType;
import java.time.Instant;

public record AiMessageResponse(
        Long id,
        AiMessageSenderType senderType,
        String message,
        Instant createdAt,
        AiFeedbackResponse feedback) {
}
