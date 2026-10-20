package com.learne.backend.jobs.service;

import com.learne.backend.jobs.config.JobProperties;
import com.learne.backend.jobs.model.JobLease;
import com.learne.backend.jobs.model.QueueName;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded execution; no transaction or connection is held around a handler call. */
public final class JobDispatcher implements AutoCloseable {
    private final JobStore store;
    private final JobRegistry registry;
    private final JobProperties.Snapshot policy;
    private final Clock clock;
    private final DoubleSupplier random;
    private final JobMetrics metrics;
    private final List<JobQueueAdmission> admissions;
    public enum AdmissionState { ALLOWED, PAUSED, ERROR }
    private final Map<QueueName, AdmissionState> admissionStates = new ConcurrentHashMap<>();
    private final Map<QueueName, Lane> lanes = new EnumMap<>(QueueName.class);
    private final ScheduledThreadPoolExecutor polls = scheduler(1, "jobs-poll");
    private final ThreadPoolExecutor pollIo = new ThreadPoolExecutor(5, 5, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), threads("jobs-poll-io"), new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean recoveryPending = new AtomicBoolean();
    // Control callbacks only submit bounded I/O or revoke locally; they never call the database.
    private final ScheduledThreadPoolExecutor controls = scheduler(1, "jobs-control");
    private final ThreadPoolExecutor heartbeats;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean stopping;
    private final Map<Thread, ActiveExecution> active = new ConcurrentHashMap<>();

    private static final class Lane {
        final Semaphore permits;
        final ThreadPoolExecutor executor;
        final AtomicBoolean pendingPoll = new AtomicBoolean();

        Lane(QueueName queue, int slots) {
            permits = new Semaphore(slots);
            // Permit reservation bounds queued + executing jobs, including executor handoff races.
            executor = new ThreadPoolExecutor(slots, slots, 0, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(slots), threads("jobs-" + queue), new ThreadPoolExecutor.AbortPolicy());
        }
    }

    public JobDispatcher(JobStore store, JobRegistry registry, JobProperties.Snapshot policy,
                         Clock clock, DoubleSupplier random) {
        this(store, registry, policy, clock, random, null);
    }

    public JobDispatcher(JobStore store, JobRegistry registry, JobProperties.Snapshot policy,
                         Clock clock, DoubleSupplier random, JobMetrics metrics) {
        this(store, registry, policy, clock, random, metrics, List.of());
    }

    public JobDispatcher(JobStore store, JobRegistry registry, JobProperties.Snapshot policy,
                         Clock clock, DoubleSupplier random, JobMetrics metrics, List<JobQueueAdmission> admissions) {
        this.store = store;
        this.registry = registry;
        this.policy = policy;
        this.clock = clock;
        this.random = random;
        this.metrics = metrics;
        this.admissions = List.copyOf(admissions);
        policy.lanes().forEach((queue, lane) -> lanes.put(queue, new Lane(queue, lane.slots())));
        int slots = policy.lanes().values().stream().mapToInt(JobProperties.LanePolicy::slots).sum();
        heartbeats = new ThreadPoolExecutor(slots, slots, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
                threads("jobs-renewal-io"), new ThreadPoolExecutor.AbortPolicy());
    }

    /** Invoked by configuration only when workers are enabled; manual dispatch remains available. */
    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) return;
        lanes.keySet().forEach(this::schedulePoll);
        try {
            polls.scheduleWithFixedDelay(() -> {
                if (!closed.get() && recoveryPending.compareAndSet(false, true)) {
                    try {
                        pollIo.execute(() -> {
                            try { if (!closed.get()) store.recoverExpired(100); }
                            catch (RuntimeException ignored) { /* Safe F6 instrumentation hook. */ }
                            finally { recoveryPending.set(false); }
                        });
                    } catch (RejectedExecutionException rejected) { recoveryPending.set(false); }
                }
            }, 5, 5, TimeUnit.SECONDS);
        } catch (RejectedExecutionException rejected) {
            if (!closed.get()) throw rejected;
        }
    }

    /** Reserve before a short, committed claim. Never invoke from a producer transaction. */
    public boolean dispatch(QueueName queue) {
        if (closed.get()) return false;
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Job dispatch cannot join a producer transaction");
        try {
            for (var admission : admissions) {
                if (!admission.allows(queue)) {
                    admissionStates.put(queue, AdmissionState.PAUSED);
                    return false;
                }
            }
            admissionStates.put(queue, AdmissionState.ALLOWED);
        } catch (RuntimeException ignored) {
            admissionStates.put(queue, AdmissionState.ERROR);
            return false;
        }
        var lane = lanes.get(queue);
        if (lane == null || registry.supported(queue).isEmpty() || !lane.permits.tryAcquire()) return false;
        boolean submitted = false;
        try {
            if (closed.get()) return false;
            var lease = store.claim(queue, registry.supported(queue), policy.lanes().get(queue).lease());
            // A claim in progress keeps its permit, but must never start work after shutdown returns.
            // Leave any late lease RUNNING for normal fenced lease recovery.
            if (lease.isEmpty() || closed.get()) return false;
            try {
                lane.executor.execute(() -> run(queue, lane, lease.get()));
                submitted = true;
                return true;
            } catch (RejectedExecutionException rejected) {
                if (!closed.get()) throw rejected;
                return false;
            }
        } finally {
            if (!submitted) lane.permits.release();
        }
    }

    private void run(QueueName queue, Lane lane, JobLease lease) {
        long startedAt = System.nanoTime();
        var worker = Thread.currentThread();
        var execution = new ActiveExecution(worker);
        var config = policy.lanes().get(queue);
        ScheduledFuture<?> heartbeat = null;
        ScheduledFuture<?> deadline = null;
        active.put(worker, execution);
        try {
            if (stopping) return;
            heartbeat = controls.scheduleWithFixedDelay(() -> {
                if (!execution.reserveRenewal()) return;
                try {
                    heartbeats.execute(() -> {
                        try {
                            if (execution.owned() && !store.heartbeat(lease, config.lease())) execution.revoke();
                        } catch (RuntimeException ignored) { execution.revoke(); }
                        finally { execution.releaseRenewal(); }
                    });
                } catch (RejectedExecutionException rejected) {
                    execution.releaseRenewal();
                    execution.revoke(); // Saturation never creates a queue or replacement renewal threads.
                }
            }, config.heartbeat().toNanos(), config.heartbeat().toNanos(), TimeUnit.NANOSECONDS);
            deadline = controls.schedule(execution::revoke, config.deadline().toNanos(), TimeUnit.NANOSECONDS);
            var handler = registry.find(lease.type(), lease.payloadVersion()).orElseThrow();
            if (lease.terminalizeOnly()) {
                if (current(execution, lease) && execution.admit(closed))
                    handler.failPermanently(lease, "EXHAUSTED_ATTEMPTS");
                return; // A failing failure callback is left for recovery, never called recursively.
            }
            if (!execution.admit(closed)) return;
            try {
                handler.execute(lease);
            } catch (Exception error) {
                if (current(execution, lease)) route(handler, lease, config, execution, error);
            }
        } catch (RuntimeException ignored) {
            // Failed fencing / failure callback: leave recovery to the lease, never job-only DEAD.
        } finally {
            execution.finish();
            if (heartbeat != null) heartbeat.cancel(false);
            if (deadline != null) deadline.cancel(false);
            active.remove(worker);
            lane.permits.release();
            if (metrics != null) metrics.duration(lease, startedAt);
            schedulePoll(queue);
        }
    }

    public AdmissionState admissionState(QueueName queue) {
        return admissionStates.getOrDefault(queue, AdmissionState.PAUSED);
    }

    private boolean current(ActiveExecution execution, JobLease lease) {
        return execution.owned() && store.isLeaseCurrent(lease) && execution.owned();
    }

    private void route(JobHandler handler, JobLease lease, JobProperties.LanePolicy config,
                       ActiveExecution execution, Exception error) {
        if (error instanceof RetryableJobException retry && lease.attempt() < lease.maxAttempts()) {
            var base = config.backoff().get(Math.min(lease.attempt() - 1, config.backoff().size() - 1));
            double sample = random.getAsDouble();
            if (!Double.isFinite(sample) || sample < 0 || sample > 1)
                throw new IllegalArgumentException("Invalid job jitter sample");
            var delay = base.plusNanos((long) (base.toNanos() * policy.jitter() * sample));
            if (retry.getRetryAfter() != null && retry.getRetryAfter().compareTo(delay) > 0)
                delay = retry.getRetryAfter();
            if (!store.retry(lease, clock.instant().plus(delay), retry.getCode()) && current(execution, lease))
                handler.failPermanently(lease, JobErrorCode.JOB_EXPIRED.name());
        } else {
            String code = error instanceof PermanentJobException permanent ? permanent.getCode()
                    : error instanceof RetryableJobException ? "EXHAUSTED_ATTEMPTS" : "HANDLER_ERROR";
            handler.failPermanently(lease, code);
        }
    }

    private void schedulePoll(QueueName queue) {
        var lane = lanes.get(queue);
        if (!started.get() || closed.get() || !lane.pendingPoll.compareAndSet(false, true)) return;
        try {
            polls.schedule(() -> {
                try {
                    pollIo.execute(() -> {
                        try { if (!closed.get()) dispatch(queue); }
                        catch (RuntimeException ignored) { /* Retry at idle cadence. */ }
                        finally {
                            lane.pendingPoll.set(false);
                            if (lane.permits.availablePermits() > 0) schedulePoll(queue);
                        }
                    });
                } catch (RejectedExecutionException rejected) {
                    lane.pendingPoll.set(false);
                    if (!closed.get()) schedulePoll(queue);
                }
            }, policy.lanes().get(queue).poll().toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException ignored) { lane.pendingPoll.set(false); }
    }

    private static ThreadFactory threads(String name) {
        return task -> {
            var thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static ScheduledThreadPoolExecutor scheduler(int count, String name) {
        var executor = new ScheduledThreadPoolExecutor(count, threads(name), new ThreadPoolExecutor.AbortPolicy());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // Start the shared budget immediately, without waiting on an in-flight database claim.
        long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        active.values().forEach(ActiveExecution::revoke);
        polls.shutdownNow();
        pollIo.shutdownNow();
        lanes.values().forEach(lane -> lane.executor.shutdown());
        try {
            for (var lane : lanes.values()) {
                long remaining = until - System.nanoTime();
                if (remaining > 0) lane.executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            stopping = true;
            active.values().forEach(ActiveExecution::revoke);
            lanes.values().forEach(lane -> lane.executor.shutdownNow());
            heartbeats.shutdownNow();
            controls.shutdownNow();
        }
    }
}
