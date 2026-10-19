package com.learne.backend.mail.service;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.jobs.service.RetryableJobException;
import java.net.*;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.mail.SimpleMailMessage;

@Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SmtpCallerDeadlineTests {
    static final class StalledResolution implements Consumer<String>, AutoCloseable {
        final CountDownLatch entered;
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch exited;
        final AtomicInteger calls = new AtomicInteger();
        StalledResolution(int slots) { entered = new CountDownLatch(slots); exited = new CountDownLatch(slots); }
        @Override public void accept(String host) {
            assertThat(host).isEqualTo("127.0.0.1");
            calls.incrementAndGet(); entered.countDown();
            // Model JVM/OS resolution which does not honor interruption.
            boolean done = false;
            while (!done) { try { release.await(); done = true; } catch (InterruptedException ignored) { } }
            exited.countDown();
        }
        @Override public void close() { release.countDown(); }
    }
    SmtpMailTransport transport(ServerSocket server, StalledResolution resolver, int deadline) {
        var p = new Properties(); p.setProperty("mail.smtp.host", "127.0.0.1");
        p.setProperty("mail.smtp.port", "" + server.getLocalPort());
        return new SmtpMailTransport(p, "", "", Duration.ofMillis(deadline), 2, resolver);
    }
    SimpleMailMessage message() {
        var m = new SimpleMailMessage(); m.setFrom("sender@example.test"); m.setTo("to@example.test");
        m.setSubject("test"); m.setText("never delivered after expiry"); return m;
    }
    String outcome(SmtpMailTransport transport) {
        try { transport.send(message()); return "SENT"; }
        catch (RetryableJobException e) { assertThat(e).hasNoCause(); return e.getCode(); }
    }
    void assertNoLateConnection(ServerSocket server, StalledResolution resolver) throws Exception {
        resolver.close();
        assertThat(resolver.exited.await(1, TimeUnit.SECONDS)).isTrue();
        server.setSoTimeout(300);
        assertThatThrownBy(() -> { try (var unexpected = server.accept()) { throw new AssertionError("late SMTP connection"); } })
                .isInstanceOf(SocketTimeoutException.class);
    }
    @Test void callerDeadlineRetainsTwoActualSlotsRejectsWithoutQueueAndForbidsLateConnect() throws Exception {
        var callers = Executors.newFixedThreadPool(2);
        try (var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var resolver = new StalledResolution(2); var t = transport(server, resolver, 300)) {
            long started = System.nanoTime();
            var a = callers.submit(() -> outcome(t)); var b = callers.submit(() -> outcome(t));
            assertThat(resolver.entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(a.get(1, TimeUnit.SECONDS)).isEqualTo("SMTP_DEADLINE");
            assertThat(b.get(1, TimeUnit.SECONDS)).isEqualTo("SMTP_DEADLINE");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1100));
            long rejectedAt = System.nanoTime();
            for (int i = 0; i < 20; i++) assertThat(outcome(t)).isEqualTo("BUSY");
            assertThat(Duration.ofNanos(System.nanoTime() - rejectedAt)).isLessThan(Duration.ofMillis(300));
            assertThat(resolver.calls.get()).isEqualTo(2);
            assertThat(Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.isAlive() && thread.getName().startsWith("smtp-io-")).count()).isEqualTo(2);
            assertNoLateConnection(server, resolver);
        } finally { callers.shutdownNow(); assertThat(callers.awaitTermination(2, TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void callerInterruptionReturnsWhileResolutionIgnoresInterruptAndCannotConnectLater() throws Exception {
        try (var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var resolver = new StalledResolution(1); var t = transport(server, resolver, 1500)) {
            var returned = new CompletableFuture<String>();
            var interrupted = new CompletableFuture<Boolean>();
            var caller = new Thread(() -> { try { returned.complete(outcome(t)); interrupted.complete(Thread.currentThread().isInterrupted()); } catch (Throwable e) { returned.completeExceptionally(e); } });
            caller.start();
            try {
                assertThat(resolver.entered.await(1, TimeUnit.SECONDS)).isTrue(); caller.interrupt();
                assertThat(returned.get(500, TimeUnit.MILLISECONDS)).isEqualTo("SMTP_INTERRUPTED");
                assertThat(interrupted.get(500, TimeUnit.MILLISECONDS)).isTrue();
                assertNoLateConnection(server, resolver);
            } finally { resolver.close(); caller.join(1000); assertThat(caller.isAlive()).isFalse(); }
        }
    }
    @Test void shutdownWakesCallerWithoutWaitingForResolutionAndForbidsLateConnect() throws Exception {
        var callers = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var resolver = new StalledResolution(1); var t = transport(server, resolver, 1500)) {
            var result = callers.submit(() -> outcome(t));
            assertThat(resolver.entered.await(1, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime(); t.close();
            assertThat(result.get(500, TimeUnit.MILLISECONDS)).isEqualTo("SMTP_INTERRUPTED");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
            assertNoLateConnection(server, resolver);
        } finally { callers.shutdownNow(); assertThat(callers.awaitTermination(2, TimeUnit.SECONDS)).isTrue(); }
    }
}
