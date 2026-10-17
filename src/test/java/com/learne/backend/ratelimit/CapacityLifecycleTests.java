package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class CapacityLifecycleTests {
    @TempDir Path directory;

    @Test
    void normalWorkerCloseReportsExitZeroAndTerminatesHttpClient() throws Exception {
        var process = new AtomicReference<Process>();
        var worker = new CapacityFork("127.0.0.1", 1, directory, 0, process::set);
        try {
            worker.close();
            assertThat(worker.metadata).containsEntry("exitCode", 0).containsEntry("terminated", true);
            assertThat(worker.httpTerminated()).isTrue();
        } finally {
            reap(process.get());
            if (!worker.httpTerminated()) worker.close();
        }
    }

    @Test
    void unexpectedWorkerExitIsReportedAfterHttpCleanup() throws Exception {
        var process = new AtomicReference<Process>();
        var worker = new CapacityFork("127.0.0.1", 1, directory, 0, process::set);
        try {
            reap(process.get());
            assertThatThrownBy(worker::close).isInstanceOf(AssertionError.class)
                    .hasRootCauseMessage("Worker exit code " + process.get().exitValue());
            assertThat(worker.httpTerminated()).isTrue();
        } finally {
            reap(process.get());
            if (!worker.httpTerminated()) worker.close();
        }
    }

    @Test
    void interruptedWorkerCloseStillTerminatesProcessAndHttpClient() throws Exception {
        var process = new AtomicReference<Process>();
        var worker = new CapacityFork("127.0.0.1", 1, directory, 0, process::set);
        var restored = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        Thread closer = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            try { worker.close(); } catch (Throwable problem) { failure.set(problem); }
            restored.set(Thread.currentThread().isInterrupted());
        });
        try {
            closer.join(12000);
            assertThat(closer.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(restored).isTrue();
            assertThat(process.get().isAlive()).isFalse();
            assertThat(worker.httpTerminated()).isTrue();
            assertThatThrownBy(() -> worker.call("/warm")).isInstanceOf(Exception.class);
        } finally {
            reap(process.get());
            closer.interrupt();
            closer.join(1000);
            if (!worker.httpTerminated()) worker.close();
        }
    }

    @Test
    void interruptedStartupFailureDestroysChildWithinBoundAndClosesHttp() throws Exception {
        var process = new AtomicReference<Process>();
        try {
            assertThatThrownBy(() -> new CapacityFork("127.0.0.1", 1, directory, 0, child -> {
                process.set(child);
                Thread.currentThread().interrupt();
            })).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(process.get().isAlive()).isFalse();
        } finally { Thread.interrupted(); reap(process.get()); }
    }

    @Test
    void interruptDuringAwaitCannotSkipOtherCleanupOrRestartTimeout() throws Exception {
        var awaiting = new CountDownLatch(1);
        var steps = new CopyOnWriteArrayList<String>();
        var restored = new AtomicBoolean();
        Thread closer = Thread.ofPlatform().start(() -> {
            try (var cleanup = new CapacityCleanup()) {
                cleanup.step(() -> {
                    assertThat(cleanup.await(nanos -> {
                        awaiting.countDown();
                        TimeUnit.NANOSECONDS.sleep(nanos);
                        return false;
                    }, Duration.ofMillis(250))).isFalse();
                    steps.add("bounded-wait");
                });
                cleanup.step(() -> { steps.add("destroy"); throw new IllegalStateException("expected"); });
                cleanup.step(() -> steps.add("http-close"));
            } catch (AssertionError expected) { steps.add("failure-reported"); }
            restored.set(Thread.currentThread().isInterrupted());
        });
        try {
            assertThat(awaiting.await(2, TimeUnit.SECONDS)).isTrue();
            closer.interrupt();
            closer.join(2000);
            assertThat(closer.isAlive()).isFalse();
            assertThat(restored).isTrue();
            assertThat(steps).containsExactly("bounded-wait", "destroy", "http-close", "failure-reported");
        } finally { closer.interrupt(); closer.join(1000); }
    }

    @Test
    void interruptedDispatcherCleanupJoinsWritersAndMonitorBeforeClosingConnection() throws Exception {
        var monitor = Executors.newSingleThreadScheduledExecutor();
        var dispatch = Executors.newVirtualThreadPerTaskExecutor();
        var running = new CountDownLatch(2);
        var monitorStopped = new AtomicBoolean();
        var writerStopped = new AtomicBoolean();
        var connectionClosed = new AtomicBoolean();
        monitor.submit(() -> blockedTask(running, monitorStopped));
        dispatch.submit(() -> blockedTask(running, writerStopped));
        try {
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.currentThread().interrupt();
            CapacityCleanup.closeWorkload(monitor, dispatch, () -> {
                assertThat(monitor.isTerminated()).isTrue();
                assertThat(dispatch.isTerminated()).isTrue();
                connectionClosed.set(true);
            });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            monitor.shutdownNow();
            dispatch.shutdownNow();
            monitor.awaitTermination(2, TimeUnit.SECONDS);
            dispatch.awaitTermination(2, TimeUnit.SECONDS);
        }
        assertThat(monitorStopped).isTrue();
        assertThat(writerStopped).isTrue();
        assertThat(connectionClosed).isTrue();
    }

    @Test
    void failedJoinsDoNotRunNormalConnectionClose() throws Exception {
        var monitor = Executors.newSingleThreadExecutor();
        var dispatch = Executors.newSingleThreadExecutor();
        var running = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var normalClose = new AtomicBoolean();
        var emergencyAbort = new AtomicBoolean();
        var reportReady = new AtomicBoolean();
        var fullReportRead = new AtomicBoolean();
        monitor.submit(() -> interruptResistantTask(running, release));
        dispatch.submit(() -> interruptResistantTask(running, release));
        try {
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            assertThatThrownBy(() -> {
                CapacityCleanup.closeWorkload(monitor, dispatch, () -> normalClose.set(true), () -> emergencyAbort.set(true));
                reportReady.set(true);
            }).isInstanceOf(AssertionError.class).hasRootCauseMessage("Monitor did not terminate")
                    .satisfies(failure -> {
                        assertThat(failure.getSuppressed()).hasSize(1);
                        assertThat(failure.getSuppressed()[0]).hasMessage("Dispatch writers did not terminate");
                    });
            assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(8));
            assertThat(monitor.isTerminated()).isFalse();
            assertThat(dispatch.isTerminated()).isFalse();
            Path report = directory.resolve("failure.json");
            CapacityReport.write(report, reportReady.get(), () -> {
                fullReportRead.set(true);
                throw new AssertionError("Mutable memory/times/samples must not be read while tasks are alive");
            });
            assertThat(fullReportRead).isFalse();
            var diagnostic = new com.fasterxml.jackson.databind.ObjectMapper().readTree(report.toFile());
            assertThat(diagnostic.path("allMeasuredGatesPassed").isBoolean()).isTrue();
            assertThat(diagnostic.path("measurementsCompleted").isBoolean()).isTrue();
            assertThat(diagnostic.path("gates").path("workloadQuiescent").isBoolean()).isTrue();
            assertThat(diagnostic.path("allMeasuredGatesPassed").booleanValue()).isFalse();
            assertThat(diagnostic.path("measurementsCompleted").booleanValue()).isFalse();
            assertThat(diagnostic.path("gates").path("workloadQuiescent").booleanValue()).isFalse();
            assertThat(diagnostic.has("samples")).isFalse();
            assertThat(diagnostic.has("memorySnapshots")).isFalse();
            assertThat(diagnostic.has("monitorOffsetsNanos")).isFalse();
            assertThat(normalClose).as("normal close requires proven joins").isFalse();
            assertThat(emergencyAbort).as("emergency abort is distinct from normal connection close").isTrue();
        } finally {
            release.countDown();
            monitor.shutdownNow();
            dispatch.shutdownNow();
            try (var cleanup = new CapacityCleanup()) {
                cleanup.step(() -> assertThat(cleanup.await(n -> monitor.awaitTermination(n, TimeUnit.NANOSECONDS), Duration.ofSeconds(2))).isTrue());
                cleanup.step(() -> assertThat(cleanup.await(n -> dispatch.awaitTermination(n, TimeUnit.NANOSECONDS), Duration.ofSeconds(2))).isTrue());
            }
        }
    }

    @Test
    void quiescentWorkloadAllowsFullMeasurementReport() throws Exception {
        Path report = directory.resolve("quiescent.json");
        CapacityReport.write(report, true, () -> java.util.Map.of("samples", List.of(1, 2), "allMeasuredGatesPassed", true));
        var evidence = new com.fasterxml.jackson.databind.ObjectMapper().readTree(report.toFile());
        assertThat(evidence.path("samples").size()).isEqualTo(2);
        assertThat(evidence.path("allMeasuredGatesPassed").booleanValue()).isTrue();
    }

    private static void interruptResistantTask(CountDownLatch running, CountDownLatch release) {
        running.countDown();
        boolean interrupted = false;
        try {
            while (true) {
                try { release.await(); return; }
                catch (InterruptedException expected) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    @Test
    void connectionCloseStillRunsWhenEarlierCleanupStepFails() {
        var closed = new AtomicBoolean();
        assertThatThrownBy(() -> {
            try (var cleanup = new CapacityCleanup()) {
                cleanup.step(() -> { throw new IllegalStateException("monitor failure"); });
                cleanup.step(() -> closed.set(true));
            }
        }).isInstanceOf(AssertionError.class);
        assertThat(closed).isTrue();
    }

    @Test
    void monitoringGapGateRejectsMissingSamplesEvenWhenCountIsLarge() {
        assertThat(CapacityMeasurements.maxGapNanos(List.of(0L, 1_012_000_000L, 2_015_000_000L)))
                .isEqualTo(1_012_000_000L);
        assertThat(CapacityMeasurements.monitoringCovered(List.of(0L, 1_012_000_000L, 2_015_000_000L), 2_015_000_000L)).isTrue();
        var times = java.util.stream.LongStream.rangeClosed(0, 61).map(i -> i * 1_000_000_000L).boxed()
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        times.remove(30); times.remove(30); // 3-second gap despite >60 retained snapshots after duplicates.
        times.add(0, 0L); times.add(0, 0L);
        assertThat(CapacityMeasurements.monitoringCovered(times, 61_000_000_000L)).isFalse();
        assertThat(CapacityMeasurements.monitoringCovered(List.of(3_000_000_000L, 4_000_000_000L), 4_000_000_000L)).isFalse();
        assertThat(CapacityMeasurements.monitoringCovered(List.of(0L, 1_000_000_000L), 4_000_000_000L)).isFalse();
        assertThat(CapacityMeasurements.monitoringCovered(List.of(0L, 2_000_000_000L, 1_000_000_000L), 2_000_000_000L)).isFalse();
        assertThat(CapacityMeasurements.monitoringCovered(List.of(0L, 0L, 0L, 0L), 60_000_000_000L)).isFalse();
    }

    @Test
    void workerExitsWhenLaunchingParentIsForciblyKilled() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path arguments = directory.resolve("parent.args");
        java.nio.file.Files.writeString(arguments, "-Xmx128m\n-cp\n\"" + classpath.replace('\\', '/')
                + "\"\n" + ParentLauncher.class.getName() + "\n\"" + directory.toString().replace('\\', '/') + "\"\n");
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process parent = new ProcessBuilder(executable, "@" + arguments).redirectErrorStream(true)
                .redirectOutput(directory.resolve("parent.log").toFile()).start();
        ProcessHandle child = null;
        try {
            Path pidFile = directory.resolve("child.pid");
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!java.nio.file.Files.exists(pidFile) && parent.isAlive() && System.nanoTime() < end) Thread.sleep(20);
            assertThat(java.nio.file.Files.exists(pidFile)).as("parent launched worker; inspect parent.log").isTrue();
            child = ProcessHandle.of(Long.parseLong(java.nio.file.Files.readString(pidFile))).orElseThrow();
            assertThat(child.isAlive()).isTrue();
            parent.destroyForcibly();
            assertThat(parent.waitFor(2, TimeUnit.SECONDS)).isTrue();
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (child.isAlive() && System.nanoTime() < end) Thread.sleep(20);
            assertThat(child.isAlive()).as("orphan worker must self-terminate").isFalse();
        } finally {
            // Also reap a worker launched just before a startup assertion failed.
            var descendants = parent.descendants().toList();
            if (child != null) child.destroyForcibly();
            descendants.forEach(ProcessHandle::destroyForcibly);
            reap(parent);
            try (var cleanup = new CapacityCleanup()) {
                for (var descendant : descendants) cleanup.step(() -> awaitDead(cleanup, descendant));
                if (child != null) {
                    var captured = child;
                    cleanup.step(() -> awaitDead(cleanup, captured));
                }
            }
        }
    }

    public static final class ParentLauncher {
        public static void main(String[] args) throws Exception {
            try (var worker = new CapacityFork("127.0.0.1", 1, Path.of(args[0]), 1)) {
                java.nio.file.Files.writeString(Path.of(args[0], "child.pid"), worker.metadata.get("pid").toString());
                new CountDownLatch(1).await();
            }
        }
    }

    private static void awaitDead(CapacityCleanup cleanup, ProcessHandle process) {
        if (!cleanup.await(nanos -> {
            long end = System.nanoTime() + nanos;
            while (process.isAlive() && System.nanoTime() < end) Thread.sleep(10);
            return !process.isAlive();
        }, Duration.ofSeconds(2))) throw new AssertionError("Test process still alive: " + process.pid());
    }

    private static void reap(Process process) {
        if (process == null) return;
        try (var cleanup = new CapacityCleanup()) {
            cleanup.step(() -> { if (process.isAlive()) process.destroyForcibly(); });
            cleanup.step(() -> {
                if (!cleanup.awaitProcess(process, Duration.ofSeconds(2)))
                    throw new AssertionError("Test process still alive: " + process.pid());
            });
        }
    }

    private static void blockedTask(CountDownLatch running, AtomicBoolean stopped) {
        running.countDown();
        try { new CountDownLatch(1).await(); }
        catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        finally { stopped.set(true); }
    }
}
