package com.learne.backend.ai.grammar.ai;

import java.util.List;

public record AiGrammarCheckResponse(List<ErrorPayload> errors) {
    public record ErrorPayload(
            String errorText,
            String errorType,
            String suggestion,
            String explanation,
            Integer startPos,
            Integer endPos) {
    }
}
