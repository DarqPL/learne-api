package com.learne.backend.jobs.service;

/** Finite, reviewed labels: provider messages and arbitrary tokens are never job error codes. */
public enum JobErrorCode {
    PAYLOAD_DECRYPTION_FAILED,
    EXHAUSTED_ATTEMPTS,
    JOB_EXPIRED,
    HANDLER_ERROR,
    INVALID_INPUT,
    MAIL_ELIGIBILITY_UNAVAILABLE,
    SMTP_INVALID_ADDRESS,
    SMTP_CONFIGURATION,
    SMTP_AUTHENTICATION,
    SMTP_REJECTED,
    SMTP_TRANSIENT,
    SMTP_CONNECTION,
    SMTP_TIMEOUT,
    SMTP_DEADLINE,
    SMTP_INTERRUPTED,
    BUSY,
    THROTTLED,
    ENRICHMENT_DISABLED,
    ENRICHMENT_CONTRACT_UNAVAILABLE,
    ENRICHMENT_CONFIGURATION_UNAVAILABLE,
    ENRICHMENT_AUTHENTICATION,
    ENRICHMENT_INVALID_REQUEST,
    ENRICHMENT_INVALID_RESPONSE,
    ENRICHMENT_RESPONSE_TOO_LARGE,
    ENRICHMENT_DEADLINE_EXCEEDED,
    ENRICHMENT_GENERATION_FAILED,
    ENRICHMENT_INTERRUPTED,
    ENRICHMENT_UNAVAILABLE;

    static String requireApproved(String code) {
        try {
            return valueOf(code).name();
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // Do not retain valueOf diagnostics: they include the untrusted input.
            throw new IllegalArgumentException("Invalid job safe code");
        }
    }
}
