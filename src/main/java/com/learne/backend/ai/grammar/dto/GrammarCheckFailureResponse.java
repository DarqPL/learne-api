package com.learne.backend.ai.grammar.dto;

public record GrammarCheckFailureResponse(Long checkId, String status, String message) {
}
