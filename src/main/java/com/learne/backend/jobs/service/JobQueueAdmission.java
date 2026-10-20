package com.learne.backend.jobs.service;

import com.learne.backend.jobs.model.QueueName;

/** Admission must be a fast, I/O-free cache read; false pauses claims without mutating jobs. */
@FunctionalInterface
public interface JobQueueAdmission {
    boolean allows(QueueName queue);
}
