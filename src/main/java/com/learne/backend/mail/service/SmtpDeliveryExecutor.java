package com.learne.backend.mail.service;

import com.learne.backend.jobs.service.RetryableJobException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Zero-queue bulkhead: cancellation never releases a thread still executing DNS/IO. */
final class SmtpDeliveryExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Set<Delivery> active = new HashSet<>();
    private boolean closed;

    SmtpDeliveryExecutor(int slots) {
        if (slots < 1) throw new IllegalArgumentException("Invalid SMTP capacity");
        var sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(slots, slots, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), r -> {
            var thread = new Thread(r, "smtp-io-" + sequence.incrementAndGet());
            thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    void execute(Runnable action, Runnable abort, long started, long budget) {
        var delivery = new Delivery(action, abort);
        synchronized (this) {
            if (closed) { delivery.cancel(); throw retry("SMTP_INTERRUPTED"); }
            active.add(delivery);
            try {
                // This wrapper, not the cancellable FutureTask, owns the actual pool slot.
                executor.execute(() -> {
                    try { delivery.result.run(); }
                    finally { synchronized (this) { active.remove(delivery); } }
                });
            } catch (RejectedExecutionException ignored) {
                active.remove(delivery); delivery.cancel(); throw retry("BUSY");
            }
        }
        try {
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            delivery.result.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ignored) {
            delivery.cancel(); throw retry("SMTP_DEADLINE");
        } catch (InterruptedException ignored) {
            delivery.cancel(); Thread.currentThread().interrupt(); throw retry("SMTP_INTERRUPTED");
        } catch (CancellationException ignored) {
            delivery.cancel(); throw retry("SMTP_INTERRUPTED");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("SMTP handler failed");
        }
    }

    @Override public synchronized void close() {
        closed = true;
        active.forEach(Delivery::cancel);
        // Do not await uninterruptible OS DNS; these fixed daemon slots remain occupied.
        executor.shutdown();
    }

    private static RetryableJobException retry(String code) { return new RetryableJobException(code, null); }
    private static final class Delivery {
        final FutureTask<Void> result;
        final Runnable abort;
        Delivery(Runnable action, Runnable abort) { this.result = new FutureTask<>(action, null); this.abort = abort; }
        void cancel() {
            // Close owned IO before waking the caller. Late task results stay inside FutureTask.
            abort.run(); result.cancel(true);
        }
    }
}
