package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.jobs.model.JobLease;
import com.learne.backend.jobs.model.JobState;
import com.learne.backend.jobs.model.JobType;
import com.learne.backend.jobs.service.JobHandler;
import com.learne.backend.jobs.service.JobStore;
import com.learne.backend.jobs.service.PermanentJobException;
import com.learne.backend.jobs.service.RetryableJobException;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.teacher.content.audio.dto.CleanupDecision;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class MediaCleanupHandler implements JobHandler {
    private final JobStore store;
    private final LessonAudioCleanupDecisionService decision;
    private final CourseCoverCleanupDecisionService courseCovers;
    private final MediaDeletePort storage;
    private final ObjectMapper mapper;

    public MediaCleanupHandler(JobStore store, LessonAudioCleanupDecisionService decision,
                               CourseCoverCleanupDecisionService courseCovers,
                               MediaDeletePort storage, ObjectMapper mapper) {
        this.store = Objects.requireNonNull(store);
        this.decision = Objects.requireNonNull(decision);
        this.courseCovers = Objects.requireNonNull(courseCovers);
        this.storage = Objects.requireNonNull(storage);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public JobType type() {
        return JobType.MEDIA_CLEANUP;
    }

    public int payloadVersion() {
        return 1;
    }

    public void execute(JobLease lease) {
        requireNoTransaction();
        if (!store.isLeaseCurrent(lease)) return;

        CleanupPayload payload = parsePayload(lease);
        CleanupDecision outcome;
        try {
            outcome = payload.resourceType() == StorageResourceType.IMAGE
                    ? courseCovers.evaluate(payload.courseId(), payload.url(), payload.publicId(), lease.jobId())
                    : decision.evaluate(payload.courseId(),
                            new LessonAudioState(payload.url(), payload.publicId()));
        } catch (RuntimeException databaseFailure) {
            throw new RetryableJobException("BUSY", null);
        }

        switch (outcome.kind()) {
            case RETAIN -> store.complete(lease, JobState.SKIPPED, outcome.reason());
            case RETRY -> throw new RetryableJobException("BUSY", null);
            case DELETE -> {
                requireNoTransaction();
                if (!store.isLeaseCurrent(lease)) return;
                if (payload.resourceType() == StorageResourceType.IMAGE) {
                    storage.delete(payload.publicId(), payload.resourceType());
                    courseCovers.completeClaim(payload.publicId(), lease.jobId());
                } else {
                    storage.delete(payload.publicId());
                }
                store.complete(lease, JobState.SUCCEEDED, null);
            }
        }
    }

    public void failPermanently(JobLease lease, String code) {
        requireNoTransaction();
        CleanupPayload payload = parsePayloadOrNull(lease);
        if (payload != null && payload.resourceType() == StorageResourceType.IMAGE) {
            courseCovers.markClaimDead(payload.publicId(), lease.jobId(), code);
        }
        store.complete(lease, JobState.DEAD, code);
    }

    private CleanupPayload parsePayload(JobLease lease) {
        try {
            JsonNode node = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(lease.payloadJson());
            if (lease.type() != type() || lease.payloadVersion() != 1
                    || node == null || !node.isObject() || node.size() != 4
                    || !node.path("courseId").isIntegralNumber() || !node.path("courseId").canConvertToLong()
                    || node.path("courseId").asLong() <= 0 || !node.path("publicId").isString()
                    || !(node.path("url").isString() || node.path("url").isNull())) {
                throw new IllegalArgumentException();
            }
            StorageResourceType resourceType = switch (node.path("resourceType").asString()) {
                case "VIDEO" -> StorageResourceType.VIDEO;
                case "IMAGE" -> StorageResourceType.IMAGE;
                default -> throw new IllegalArgumentException();
            };
            return new CleanupPayload(node.path("courseId").asLong(),
                    node.path("url").isNull() ? null : node.path("url").asString(),
                    node.path("publicId").asString(), resourceType);
        } catch (Exception invalid) {
            throw new PermanentJobException("INVALID_INPUT");
        }
    }

    private CleanupPayload parsePayloadOrNull(JobLease lease) {
        try {
            return parsePayload(lease);
        } catch (PermanentJobException invalid) {
            return null;
        }
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Media worker requires no ambient transaction");
        }
    }

    private record CleanupPayload(long courseId, String url, String publicId, StorageResourceType resourceType) {
    }
}
