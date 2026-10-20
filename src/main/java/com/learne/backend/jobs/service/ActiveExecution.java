package com.learne.backend.jobs.service;

import java.util.concurrent.atomic.AtomicBoolean;

/** Local admission/revocation only. No lock here may enclose handler or database I/O. */
final class ActiveExecution {
    private enum State { WAITING, ADMITTED, REVOKED, FINISHED }
    private State state = State.WAITING;
    private final Thread worker;
    private final AtomicBoolean renewalPending = new AtomicBoolean();

    ActiveExecution(Thread worker) { this.worker = worker; }

    synchronized boolean admit(AtomicBoolean closed) {
        if (closed.get() || state != State.WAITING) return false;
        // This transition is the admission point. Revocation after it is best-effort cancellation.
        state = State.ADMITTED;
        return true;
    }

    synchronized boolean owned() { return state == State.WAITING || state == State.ADMITTED; }

    synchronized void revoke() {
        if (!owned()) return;
        state = State.REVOKED;
        worker.interrupt();
    }

    synchronized void finish() {
        state = State.FINISHED;
        // Serialize cleanup against revocation; a late timer cannot interrupt a reused worker.
        Thread.interrupted();
    }

    boolean reserveRenewal() { return owned() && renewalPending.compareAndSet(false, true); }
    void releaseRenewal() { renewalPending.set(false); }
}
