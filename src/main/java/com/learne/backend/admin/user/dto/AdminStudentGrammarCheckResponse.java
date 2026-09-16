package com.learne.backend.admin.user.dto;

import java.time.Instant;

public record AdminStudentGrammarCheckResponse(
        Long id,
        String originalText,
        String correctedText,
        Instant createdAt) {
}
