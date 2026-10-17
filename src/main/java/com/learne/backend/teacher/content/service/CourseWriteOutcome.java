package com.learne.backend.teacher.content.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Per-attempt completion evidence; callbacks deliberately perform no I/O. */
public final class CourseWriteOutcome {
    private volatile int status = TransactionSynchronization.STATUS_UNKNOWN;

    public void observe() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Course writes require an active synchronized transaction.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int completionStatus) {
                status = completionStatus;
            }
        });
    }

    public boolean rolledBack() {
        return status == TransactionSynchronization.STATUS_ROLLED_BACK;
    }
}
