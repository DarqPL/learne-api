package com.learne.backend.teacher.content.enrichment;

import com.learne.backend.jobs.model.QueueName;
import com.learne.backend.jobs.service.JobQueueAdmission;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/** Dedicated refresh lane; claim admission reads only the finite, volatile cache. */
public final class EnrichmentCapabilityGate implements JobQueueAdmission, AutoCloseable {
    public enum SafeState { PAUSED, READY, DISABLED, UNAVAILABLE, SOURCE_FAILURE, UNSUPPORTED_POLICY, UNSUPPORTED_RESPONSE, UNSUPPORTED_TUPLE, CLOSED }
    private final EnrichmentCapabilities capabilities;
    private final boolean enabled;
    private final boolean workerEnabled;
    private final Supplier<List<EnrichmentContractSource>> sources;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor scheduler;
    private volatile SafeState state;
    private volatile boolean closed;
    private volatile int failures;

    public EnrichmentCapabilityGate(EnrichmentCapabilities capabilities, boolean enabled, boolean workerEnabled,
                                    Supplier<List<EnrichmentContractSource>> sources) {
        this.capabilities = capabilities;
        this.enabled = enabled;
        this.workerEnabled = workerEnabled;
        this.sources = sources;
        state = enabled ? SafeState.PAUSED : SafeState.DISABLED;
        scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "enrichment-capability-io");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (closed || !enabled || !workerEnabled || !started.compareAndSet(false, true)) return;
        schedule(0);
    }

    private void schedule(long seconds) {
        if (closed) return;
        try {
            scheduler.schedule(() -> {
                try { refresh(); }
                finally { if (!closed) schedule(nextRefreshDelaySeconds()); }
            }, seconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) { /* Closed concurrently. */ }
    }

    /** Refresh-time I/O only; never invoked by job timers or claims. Concurrent refreshes are dropped. */
    public void refresh() {
        if (closed || !enabled || !refreshing.compareAndSet(false, true)) return;
        SafeState result = SafeState.UNAVAILABLE;
        try {
            Set<EnrichmentContract> required = new HashSet<>();
            required.add(EnrichmentContract.V1);
            try {
                for (var source : List.copyOf(sources.get())) required.addAll(Set.copyOf(source.activeContracts()));
            } catch (RuntimeException ignored) { result = SafeState.SOURCE_FAILURE; return; }
            if (required.stream().anyMatch(contract -> !"1".equals(contract.policyVersion()))) {
                result = SafeState.UNSUPPORTED_POLICY;
                return;
            }
            if (required.stream().anyMatch(contract -> !EnrichmentResponseSupport.supports(contract.responseVersion()))) {
                result = SafeState.UNSUPPORTED_RESPONSE;
                return;
            }
            var remote = capabilities.fetch();
            result = required.stream().allMatch(contract -> remote.contains(
                    new EnrichmentCapabilities.Tuple(contract.promptVersion(), contract.responseVersion())))
                    ? SafeState.READY : SafeState.UNSUPPORTED_TUPLE;
        } catch (RuntimeException ignored) { result = SafeState.UNAVAILABLE; }
        finally {
            publish(result);
            refreshing.set(false);
        }
    }

    private synchronized void publish(SafeState result) {
        if (closed) return;
        failures = result == SafeState.READY ? 0 : Math.min(4, failures + 1);
        state = result;
    }

    public SafeState state() { return state; }

    public long nextRefreshDelaySeconds() {
        if (state == SafeState.READY) return 15;
        return switch (failures) { case 0, 1 -> 5; case 2 -> 10; case 3 -> 30; default -> 60; };
    }

    @Override public boolean allows(QueueName queue) { return queue != QueueName.AI || state == SafeState.READY; }

    @Override public synchronized void close() {
        closed = true;
        state = SafeState.CLOSED;
        scheduler.shutdownNow();
    }
}
