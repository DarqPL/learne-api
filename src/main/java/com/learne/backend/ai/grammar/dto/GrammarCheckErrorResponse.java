package com.learne.backend.ai.grammar.dto;

import com.learne.backend.ai.grammar.enums.GrammarCheckErrorType;

public record GrammarCheckErrorResponse(
        Long id,
        int sequenceOrder,
        String errorText,
        GrammarCheckErrorType errorType,
        String suggestion,
        String explanation,
        int startPos,
        int endPos) {
}
