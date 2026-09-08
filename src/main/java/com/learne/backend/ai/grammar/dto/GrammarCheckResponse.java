package com.learne.backend.ai.grammar.dto;

import com.learne.backend.ai.grammar.enums.GrammarCheckStatus;
import java.time.Instant;
import java.util.List;

public record GrammarCheckResponse(
        Long id,
        GrammarCheckStatus status,
        String inputText,
        Long courseId,
        Long lessonId,
        Long questionId,
        Long enrollmentId,
        String failureMessage,
        Instant createdAt,
        Instant completedAt,
        List<GrammarCheckErrorResponse> errors) {
}
