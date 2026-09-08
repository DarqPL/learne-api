package com.learne.backend.ai.tutor.dto;

public record SendAiTutorMessageResponse(
        AiMessageResponse userMessage,
        AiMessageResponse aiMessage,
        AiConversationSummaryResponse conversation) {
}
