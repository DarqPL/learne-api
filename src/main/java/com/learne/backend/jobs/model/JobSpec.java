package com.learne.backend.jobs.model;

import java.time.Instant;
import java.util.UUID;

public record JobSpec(UUID id, QueueName queue, JobType type, int payloadVersion, int priority,
        String dedupeKey, String payloadJson, Instant expiresAt, Ciphertext secret) {
}
