package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class CapacityProcessJoinTests {
    @TempDir Path directory;

    @Test
    void forcedNativeTerminationReleasesRedirectedLogBeforeJoinReturns() throws Exception {
        for (int index = 0; index < 20; index++) {
            Path log = directory.resolve("child-" + index + ".log");
            var child = new ObservedProcess(startChild(log));
            try {
                child.destroyForcibly();
                try (var cleanup = new CapacityCleanup()) {
                    assertThat(cleanup.awaitProcess(child, Duration.ofSeconds(2))).isTrue();
                }
                Files.delete(log); // No retry: a successful join must release the native handle.
                child.assertWaiterStopped();
            } finally { nativeReap(child); }
        }
    }

    @Test
    void liveChildTimesOutWithinOperationBoundAndLeavesNoWaiter() throws Exception {
        var child = new ObservedProcess(startChild(directory.resolve("live.log")));
        try {
            long start = System.nanoTime();
            try (var cleanup = new CapacityCleanup()) {
                assertThat(cleanup.awaitProcess(child, Duration.ofMillis(250))).isFalse();
            }
            assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(1));
            assertThat(child.isAlive()).isTrue();
            child.assertWaiterStopped();
        } finally { nativeReap(child); }
    }

    @Test
    void alreadyDeadProcessStillUsesNativeWaitAndJoinsItsWaiter() throws Exception {
        var child = new ObservedProcess(startChild(directory.resolve("dead.log")));
        try {
            nativeReap(child.delegate);
            try (var cleanup = new CapacityCleanup()) {
                assertThat(cleanup.awaitProcess(child, Duration.ofSeconds(2))).isTrue();
            }
            child.assertWaiterStopped();
        } finally { nativeReap(child); }
    }

    @Test
    void preInterruptedCleanupRestoresInterruptAfterNativeJoin() throws Exception {
        var child = new ObservedProcess(startChild(directory.resolve("interrupted.log")));
        try {
            child.destroyForcibly();
            Thread.currentThread().interrupt();
            try (var cleanup = new CapacityCleanup()) {
                assertThat(cleanup.awaitProcess(child, Duration.ofSeconds(2))).isTrue();
            }
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            child.assertWaiterStopped();
            Files.delete(directory.resolve("interrupted.log"));
        } finally { Thread.interrupted(); nativeReap(child); }
    }

    @Test
    void interruptDuringNativeJoinDoesNotRestartBoundAndRestoresInterrupt() throws Exception {
        var child = new ObservedProcess(startChild(directory.resolve("during-wait.log")));
        var failure = new AtomicReference<Throwable>();
        var restored = new java.util.concurrent.atomic.AtomicBoolean();
        Thread closer = Thread.ofPlatform().start(() -> {
            try (var cleanup = new CapacityCleanup()) {
                assertThat(cleanup.awaitProcess(child, Duration.ofMillis(500))).isFalse();
            } catch (Throwable problem) { failure.set(problem); }
            restored.set(Thread.currentThread().isInterrupted());
        });
        try {
            long start = System.nanoTime();
            assertThat(child.entered.await(2, TimeUnit.SECONDS)).isTrue();
            closer.interrupt();
            assertThat(closer.join(Duration.ofSeconds(2))).isTrue();
            assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(1));
            assertThat(failure.get()).isNull();
            assertThat(restored).isTrue();
            child.assertWaiterStopped();
        } finally {
            closer.interrupt();
            try { nativeReap(child.delegate); }
            finally { assertThat(closer.join(Duration.ofSeconds(1))).isTrue(); }
        }
    }

    @Test
    void nativeWaitFailureIsPropagatedAfterWaiterIsJoined() throws Exception {
        var child = new ObservedProcess(startChild(directory.resolve("wait-failure.log"))) {
            public int waitFor() throws InterruptedException {
                super.waitFor();
                throw new IllegalStateException("native wait failure");
            }
        };
        try {
            nativeReap(child.delegate);
            assertThatThrownBy(() -> {
                try (var cleanup = new CapacityCleanup()) {
                    cleanup.awaitProcess(child, Duration.ofSeconds(2));
                }
            }).isInstanceOf(AssertionError.class).hasRootCauseMessage("native wait failure");
            child.assertWaiterStopped();
        } finally { nativeReap(child.delegate); }
    }

    private Process startChild(Path log) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(executable, "-cp", classpath, LogWorker.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (Files.size(log) == 0 && child.isAlive() && System.nanoTime() < end)
                TimeUnit.MILLISECONDS.sleep(1);
            assertThat(Files.readString(log)).startsWith("ready");
            return child;
        } catch (Throwable problem) { nativeReap(child); throw problem; }
    }

    private static class ObservedProcess extends Process {
        final Process delegate;
        final AtomicReference<Thread> waiter = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1);

        ObservedProcess(Process delegate) { this.delegate = delegate; }
        public java.io.OutputStream getOutputStream() { return delegate.getOutputStream(); }
        public java.io.InputStream getInputStream() { return delegate.getInputStream(); }
        public java.io.InputStream getErrorStream() { return delegate.getErrorStream(); }
        public int waitFor() throws InterruptedException {
            waiter.set(Thread.currentThread());
            entered.countDown();
            return delegate.waitFor();
        }
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.waitFor(timeout, unit);
        }
        public int exitValue() { return delegate.exitValue(); }
        public void destroy() { delegate.destroy(); }
        public Process destroyForcibly() { delegate.destroyForcibly(); return this; }
        public long pid() { return delegate.pid(); }

        void assertWaiterStopped() {
            assertThat(waiter.get()).as("even an observed exit needs the native barrier").isNotNull();
            assertThat(waiter.get()).isNotSameAs(Thread.currentThread());
            assertThat(waiter.get().isAlive()).isFalse();
        }
    }

    // Independent test-owned final reap, including on RED; never wait unbounded on the test thread.
    private static void nativeReap(Process child) throws Exception {
        child.destroyForcibly();
        var result = new FutureTask<Integer>(child::waitFor);
        Thread waiter = Thread.ofVirtual().start(result);
        try { result.get(3, TimeUnit.SECONDS); }
        finally {
            waiter.interrupt();
            assertThat(waiter.join(Duration.ofSeconds(1))).isTrue();
        }
    }

    public static final class LogWorker {
        public static void main(String[] args) throws Exception {
            System.out.println("ready");
            System.out.flush();
            new CountDownLatch(1).await();
        }
    }
}
