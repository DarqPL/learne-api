package com.learne.backend.ratelimit;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Test lifecycle cleanup; each operation must have its own bounded wait. */
final class CapacityCleanup implements AutoCloseable {
    interface Step { void run() throws Exception; }
    interface TimedWait { boolean await(long nanos) throws InterruptedException; }
    private final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    private boolean interrupted = Thread.interrupted();
    private AssertionError failure;

    void step(Step step) {
        interrupted |= Thread.interrupted();
        try { step.run(); }
        catch (InterruptedException problem) { interrupted = true; }
        catch (Throwable problem) {
            if (failure == null) failure = new AssertionError("Capacity cleanup failed", problem);
            else failure.addSuppressed(problem);
        } finally { interrupted |= Thread.interrupted(); }
    }

    boolean await(TimedWait wait, Duration timeout) {
        long end = Math.min(deadline, System.nanoTime() + timeout.toNanos());
        return awaitUntil(wait, end);
    }

    private boolean awaitUntil(TimedWait wait, long end) {
        interrupted |= Thread.interrupted();
        while (true) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0) return false;
            try { return wait.await(remaining); }
            catch (InterruptedException problem) { interrupted = true; }
        }
    }

    boolean awaitProcess(Process process, Duration timeout) {
        long now = System.nanoTime();
        long remaining = Math.min(deadline - now, timeout.toNanos());
        if (remaining <= 0) return false;
        long end = now + remaining;
        // Reserve waiter teardown time inside both the operation bound and total cleanup deadline.
        long joinBudget = Math.min(TimeUnit.MILLISECONDS.toNanos(100), remaining / 2);
        var result = new FutureTask<Integer>(process::waitFor);
        // Windows timed wait/isAlive can see the exit code before TerminateProcess releases files.
        // Only unconditional waitFor supplies the native-termination barrier, even if already dead.
        Thread waiter = Thread.ofVirtual().name("capacity-process-wait-" + process.pid()).start(result);
        try {
            return awaitUntil(n -> {
                try { result.get(n, TimeUnit.NANOSECONDS); return true; }
                catch (TimeoutException problem) { return false; }
                catch (ExecutionException problem) {
                    throw new AssertionError("Native process wait failed: " + process.pid(), problem.getCause());
                }
            }, end - joinBudget);
        } finally {
            waiter.interrupt();
            if (!awaitUntil(n -> waiter.join(Duration.ofNanos(n)), end))
                throw new AssertionError("Process waiter did not terminate: " + process.pid());
        }
    }

    static void closeWorkload(ExecutorService monitor, ExecutorService dispatch, Step closeConnection) {
        closeWorkload(monitor, dispatch, closeConnection, () -> { });
    }

    /** emergencyAbort must only initiate cancellation (nonblocking); it does not prove termination. */
    static void closeWorkload(ExecutorService monitor, ExecutorService dispatch, Step closeConnection, Step emergencyAbort) {
        var joined = new boolean[2];
        try (var cleanup = new CapacityCleanup()) {
            cleanup.step(() -> monitor.shutdownNow());
            cleanup.step(() -> dispatch.shutdownNow());
            cleanup.step(() -> {
                joined[0] = cleanup.await(n -> monitor.awaitTermination(n, TimeUnit.NANOSECONDS), Duration.ofSeconds(3))
                        && monitor.isTerminated();
                if (!joined[0])
                    throw new AssertionError("Monitor did not terminate");
            });
            cleanup.step(() -> {
                joined[1] = cleanup.await(n -> dispatch.awaitTermination(n, TimeUnit.NANOSECONDS), Duration.ofSeconds(3))
                        && dispatch.isTerminated();
                if (!joined[1])
                    throw new AssertionError("Dispatch writers did not terminate");
            });
            // A timeout stays a failure even if cancellation subsequently unblocks the tasks.
            // Only successful joins permit normal close and the caller's full-report path.
            if (joined[0] && joined[1]) cleanup.step(closeConnection);
            else cleanup.step(emergencyAbort);
        }
    }
    public void close() {
        interrupted |= Thread.interrupted();
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) throw failure;
    }
}
