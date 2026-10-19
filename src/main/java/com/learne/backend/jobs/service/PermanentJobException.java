package com.learne.backend.jobs.service;

public class PermanentJobException extends RuntimeException {
    private final String code;

    public PermanentJobException(String code) {
        super(JobErrorCode.requireApproved(code), null, false, false);
        this.code = code;
    }

    public String getCode() { return code; }
}
