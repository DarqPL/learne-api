package com.learne.backend.ai.grammar.dto;

import com.learne.backend.ai.grammar.enums.GrammarCheckStatus;
import java.time.Instant;

public record GrammarCheckListItemResponse(
        Long id,
        GrammarCheckStatus status,
        String inputPreview,
        long errorCount,
        String failureMessage,
        Instant createdAt,
        Instant completedAt) {
}
