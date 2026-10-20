package com.learne.backend.jobs.service;

import com.learne.backend.jobs.model.JobLease;
import com.learne.backend.jobs.model.JobType;

/** Implementations own fenced durable completion and atomic domain failure. */
public interface JobHandler {
    JobType type();
    int payloadVersion();
    void execute(JobLease lease);
    void failPermanently(JobLease lease, String code);
}
