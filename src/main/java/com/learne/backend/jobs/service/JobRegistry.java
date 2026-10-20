package com.learne.backend.jobs.service;

import com.learne.backend.jobs.model.JobType;
import com.learne.backend.jobs.model.QueueName;
import java.util.*;

/** Exact capabilities only; an empty deployment is a valid foundation. */
public final class JobRegistry {
    private record Key(JobType type, int version) {}
    private final Map<Key, JobHandler> handlers;
    private final Map<QueueName, Map<JobType, Set<Integer>>> capabilities;

    public JobRegistry(List<JobHandler> registrations) {
        var indexed = new HashMap<Key, JobHandler>();
        var supported = new EnumMap<QueueName, Map<JobType, Set<Integer>>>(QueueName.class);
        for (var queue : QueueName.values()) supported.put(queue, new EnumMap<>(JobType.class));
        for (var handler : registrations) {
            if (handler == null || handler.type() == null || handler.payloadVersion() < 1)
                throw new IllegalArgumentException("Invalid job handler capability");
            var key = new Key(handler.type(), handler.payloadVersion());
            if (indexed.putIfAbsent(key, handler) != null)
                throw new IllegalArgumentException("Duplicate job handler capability");
            supported.get(queueFor(key.type())).computeIfAbsent(key.type(), ignored -> new HashSet<>()).add(key.version());
        }
        var frozen = new EnumMap<QueueName, Map<JobType, Set<Integer>>>(QueueName.class);
        supported.forEach((queue, types) -> {
            var versions = new EnumMap<JobType, Set<Integer>>(JobType.class);
            types.forEach((type, values) -> versions.put(type, Set.copyOf(values)));
            frozen.put(queue, Map.copyOf(versions));
        });
        handlers = Map.copyOf(indexed);
        capabilities = Map.copyOf(frozen);
    }

    public Optional<JobHandler> find(JobType type, int version) {
        return Optional.ofNullable(handlers.get(new Key(type, version)));
    }

    public Map<JobType, Set<Integer>> supported(QueueName queue) { return capabilities.get(queue); }

    public static QueueName queueFor(JobType type) {
        return switch (type) {
            case COURSE_ENRICHMENT -> QueueName.AI;
            case REGISTRATION_OTP_MAIL, PASSWORD_RESET_MAIL, TEACHER_INVITATION_MAIL -> QueueName.MAIL_AUTH;
            case PAYMENT_CONFIRMATION_MAIL, COURSE_PENDING_REVIEW_MAIL, COURSE_REVIEW_RESULT_MAIL -> QueueName.MAIL_NOTICE;
            case MEDIA_CLEANUP -> QueueName.MEDIA;
        };
    }
}
