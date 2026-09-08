package com.learne.backend.ai.grammar.ai;

import java.util.List;

public record AiGrammarCheckRequest(String inputText, ConstraintPayload constraints) {
    public static AiGrammarCheckRequest create(String inputText) {
        return new AiGrammarCheckRequest(inputText, new ConstraintPayload(
                20,
                List.of("GRAMMAR", "SPELLING", "PUNCTUATION", "WORD_CHOICE", "STYLE", "OTHER"),
                "ZERO_BASED_END_EXCLUSIVE"));
    }

    public record ConstraintPayload(int maxErrors, List<String> allowedErrorTypes, String positionBase) {
    }
}
