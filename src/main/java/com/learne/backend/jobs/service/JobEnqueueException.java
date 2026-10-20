package com.learne.backend.jobs.service;

public class JobEnqueueException extends IllegalStateException {
    public JobEnqueueException(Throwable cause) {
        // JDBC/server diagnostics can contain entire payloads, even in nested or suppressed causes.
        // Deliberately discard them and initialize an empty cause so none can be reattached.
        super("QUEUE_WRITE_UNAVAILABLE", null);
    }
}
