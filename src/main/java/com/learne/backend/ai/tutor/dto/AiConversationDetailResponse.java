package com.learne.backend.ai.tutor.dto;

import com.learne.backend.ai.tutor.enums.AiConversationStatus;
import com.learne.backend.ai.tutor.enums.AiTutorPracticeType;
import java.time.Instant;
import java.util.List;

public record AiConversationDetailResponse(
        Long id,
        String title,
        AiTutorPracticeType practiceType,
        AiConversationStatus status,
        Instant startedAt,
        Instant lastMessageAt,
        Instant cleanupWarnAt,
        Instant archiveAfter,
        Instant deleteAfter,
        Boolean cleanupWarning,
        List<AiMessageResponse> messages) {
}
