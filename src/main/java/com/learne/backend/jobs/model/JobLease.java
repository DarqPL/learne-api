package com.learne.backend.jobs.model;

import java.time.Instant;
import java.util.UUID;

public record JobLease(UUID jobId, UUID token, Instant leaseUntil, JobType type, int payloadVersion,
        int attempt, int maxAttempts, String payloadJson, Ciphertext secret, boolean terminalizeOnly) {
}
