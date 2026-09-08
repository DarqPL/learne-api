package com.learne.backend.ai.grammar.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public record SubmitGrammarCheckRequest(
        @NotBlank String inputText,
        @Valid GrammarCheckContextRequest context) {
}
