package com.learne.backend.jobs.service;

import com.learne.backend.jobs.model.JobSpec;
import java.util.UUID;

public interface JobEnqueuer {
    UUID enqueue(JobSpec job);
}
