package com.learne.backend.jobs.service;

import org.springframework.stereotype.Service;

/** Reschedules expired leases; owning handlers perform domain terminalization later. */
@Service
public class JobRecoveryService {
    private final JobStore store;

    public JobRecoveryService(JobStore store) { this.store = store; }

    public int recoverExpired(int limit) { return store.recoverExpired(limit); }
}
