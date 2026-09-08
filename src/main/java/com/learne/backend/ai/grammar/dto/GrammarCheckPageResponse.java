package com.learne.backend.ai.grammar.dto;

import java.util.List;

public record GrammarCheckPageResponse(
        List<GrammarCheckListItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
