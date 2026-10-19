package com.learne.backend.mail.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Mail metadata only; codes and passwords belong in separate encrypted secret storage. */
public record MailPayload(
        long recipientUserId,
        String recipientEmail,
        String recipientName,
        MailTemplate template,
        String generation,
        Instant expiresAt,
        Long courseId,
        Long revisionId,
        UUID submissionId,
        UUID decisionId,
        Long orderId,
        Map<String, String> values) {

    public MailPayload {
        values = Map.copyOf(values);
    }

    @Override
    public String toString() {
        return "MailPayload[template=" + template + ", metadata=[REDACTED]]";
    }
}
