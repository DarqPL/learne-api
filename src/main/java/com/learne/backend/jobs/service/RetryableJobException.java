package com.learne.backend.jobs.service;

import java.time.Duration;

public class RetryableJobException extends RuntimeException {
    private final String code;
    private final Duration retryAfter;

    public RetryableJobException(String code, Duration retryAfter) {
        super(JobErrorCode.requireApproved(code), null, false, false);
        if (retryAfter != null && retryAfter.isNegative())
            throw new IllegalArgumentException("Invalid job retry delay");
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public String getCode() { return code; }
    public Duration getRetryAfter() { return retryAfter; }
}
