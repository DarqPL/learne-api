package com.learne.backend.ai.tutor.dto;

import java.util.List;

public record AiConversationPageResponse(
        List<AiConversationSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
