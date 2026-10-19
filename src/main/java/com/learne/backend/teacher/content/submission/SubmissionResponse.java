package com.learne.backend.teacher.content.submission;

import java.time.Instant;
import java.util.UUID;

/** Public submission summary; internal contract and content fingerprints are never exposed. */
public record SubmissionResponse(UUID id, Long courseId, Long revisionId, String status,
        int processedQuestions, int totalQuestions, Instant createdAt, Instant completedAt,
        String failureCode, String message) {
    public boolean editingLocked() {
        return "QUEUED".equals(status) || "PROCESSING".equals(status);
    }
}
