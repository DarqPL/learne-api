package com.learne.backend.ai.tutor.dto;

import com.learne.backend.ai.tutor.enums.AiConversationStatus;
import com.learne.backend.ai.tutor.enums.AiTutorPracticeType;
import java.time.Instant;

public record AiConversationSummaryResponse(
        Long id,
        String title,
        AiTutorPracticeType practiceType,
        AiConversationStatus status,
        String messagePreview,
        Instant lastMessageAt,
        Instant cleanupWarnAt,
        Instant archiveAfter,
        Boolean cleanupWarning) {
}
