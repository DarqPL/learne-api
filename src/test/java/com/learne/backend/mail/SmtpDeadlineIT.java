package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.mail.service.SmtpMailTransport;
import com.learne.backend.jobs.service.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SmtpDeadlineIT {
    enum Mode { SUCCESS, PAUSED_SUCCESS, REJECT550, REJECT451, AUTH535, GREETING, DATA, TRICKLE, LOST_ACK, LOST_ACK_CLOSE, STARTTLS, BLOCKED_WRITE }
    static final class Peer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final CountDownLatch active = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final CompletableFuture<Boolean> closed = new CompletableFuture<>();
        final CompletableFuture<byte[]> data = new CompletableFuture<>();
        volatile Socket socket;
        Peer(Mode mode) throws IOException {
            this(mode, "private-address");
        }
        Peer(Mode mode, String diagnostic) throws IOException {
            executor.submit(() -> {
                try (var s = server.accept()) {
                    socket = s; s.setSoTimeout(2500); active.countDown();
                    var in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    var out = s.getOutputStream();
                    if (mode == Mode.PAUSED_SUCCESS && !resume.await(2, TimeUnit.SECONDS)) throw new AssertionError("Peer not resumed");
                    if (mode == Mode.GREETING) { closed.complete(in.read() == -1); return; }
                    if (mode == Mode.TRICKLE) {
                        try { for (int i = 0; i < 30; i++) { out.write('2'); out.flush(); Thread.sleep(40); } }
                        catch (IOException ignored) { }
                        closed.complete(in.read() == -1); return;
                    }
                    reply(out, "220 loopback");
                    String line;
                    boolean body = false;
                    var bytes = new ByteArrayOutputStream();
                    while ((line = in.readLine()) != null) {
                        if (body) {
                            if (line.equals(".")) {
                                data.complete(bytes.toByteArray()); body = false;
                                if (mode == Mode.LOST_ACK_CLOSE) { closed.complete(true); return; }
                                if (mode == Mode.LOST_ACK) { closed.complete(in.read() == -1); return; }
                                reply(out, "250 accepted");
                            } else bytes.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
                        } else if (line.startsWith("EHLO")) {
                            reply(out, mode == Mode.AUTH535 ? "250-loopback\r\n250 AUTH LOGIN PLAIN" : mode == Mode.STARTTLS ? "250-loopback\r\n250 STARTTLS" : "250 loopback");
                        } else if (line.equals("STARTTLS")) {
                            reply(out, "220 start TLS");
                            // Drain raw TLS handshake bytes until raw socket abort, without TLS close-notify.
                            while (s.getInputStream().read() != -1) { }
                            closed.complete(true); return;
                        } else if (line.startsWith("AUTH")) reply(out, "535 private-provider-diagnostic");
                        else if (line.startsWith("MAIL")) reply(out, "250 ok");
                        else if (line.startsWith("RCPT")) reply(out, mode == Mode.REJECT550 ? "550 " + diagnostic : mode == Mode.REJECT451 ? "451 " + diagnostic : "250 ok");
                        else if (line.equals("DATA")) {
                            if (mode == Mode.DATA) { closed.complete(in.read() == -1); return; }
                            reply(out, "354 continue"); body = true;
                            if (mode == Mode.BLOCKED_WRITE) {
                                Thread.sleep(600);
                                while (in.read() != -1) { }
                                closed.complete(true); return;
                            }
                        } else if (line.equals("QUIT")) { reply(out, "221 bye"); }
                        else reply(out, "250 ok");
                    }
                    closed.complete(true);
                } catch (SocketException e) { closed.complete(true); }
                catch (Exception e) { closed.completeExceptionally(e); }
            });
        }
        static void reply(OutputStream out, String line) throws IOException { out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush(); }
        int port() { return server.getLocalPort(); }
        public void close() throws Exception { server.close(); if (socket != null) socket.close(); executor.shutdownNow(); assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue(); }
    }
    SmtpMailTransport transport(Peer p, int deadline) {
        var props = SmtpMailTransportTests.settings(p.port());
        props.setProperty("mail.smtp.timeout", "250");
        return new SmtpMailTransport(props, "", "", Duration.ofMillis(deadline));
    }
    @Test void successPreservesUtf8AndClosesConnection() throws Exception {
        try (var p = new Peer(Mode.SUCCESS); var t = transport(p, 1500)) {
            t.send(SmtpMailTransportTests.message());
            var mime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(p.data.get(2, TimeUnit.SECONDS)));
            assertThat(mime.getSubject()).isEqualTo("Xin chào Việt Nam");
            assertThat(mime.getContent().toString()).contains("Nội dung tiếng Việt");
            assertThat(mime.getMessageID()).isNotBlank();
            assertThat(mime.getSentDate()).isNotNull();
            assertThat(mime.getAllRecipients()).hasSize(1);
            assertThat(p.closed.get(2, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void smtpReplyCodesAreFiniteAndDiscardProviderDetails() throws Exception {
        for (var mode : new Mode[]{Mode.REJECT550, Mode.REJECT451, Mode.AUTH535}) {
            try (var p = new Peer(mode)) {
                var props = SmtpMailTransportTests.settings(p.port());
                props.setProperty("mail.smtp.auth", "" + (mode == Mode.AUTH535));
                try (var t = new SmtpMailTransport(props, "private-user", "private-password", Duration.ofMillis(1000))) {
                    assertThatThrownBy(() -> t.send(SmtpMailTransportTests.message()))
                            .isInstanceOf(mode == Mode.REJECT451 ? RetryableJobException.class : PermanentJobException.class)
                            .hasNoCause().hasMessage(mode == Mode.REJECT451 ? "SMTP_TRANSIENT" : mode == Mode.AUTH535 ? "SMTP_AUTHENTICATION" : "SMTP_REJECTED");
                    assertThat(p.closed.get(2, TimeUnit.SECONDS)).isTrue();
                }
            }
        }
    }
    @Test void stalledGreetingDataAndLostAckCloseActualSocket() throws Exception {
        for (var mode : new Mode[]{Mode.GREETING, Mode.DATA, Mode.LOST_ACK}) {
            try (var p = new Peer(mode); var t = transport(p, 400)) {
                assertThatThrownBy(() -> t.send(SmtpMailTransportTests.message())).isInstanceOf(RetryableJobException.class).hasNoCause();
                assertThat(p.closed.get(2, TimeUnit.SECONDS)).isTrue();
                // LOST_ACK means DATA may have been accepted: retry can duplicate delivery.
                if (mode == Mode.LOST_ACK) assertThat(p.data.get(2, TimeUnit.SECONDS)).isNotEmpty();
            }
        }
    }
    @Test void absoluteDeadlineDefeatsTrickledReply() throws Exception {
        try (var p = new Peer(Mode.TRICKLE); var t = transport(p, 300)) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> t.send(SmtpMailTransportTests.message())).hasMessage("SMTP_DEADLINE").hasNoCause();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            assertThat(p.closed.get(2, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void disconnectedAfterDataIsRetryableDespiteUnknownAcceptance() throws Exception {
        try (var p = new Peer(Mode.LOST_ACK_CLOSE); var t = transport(p, 1000)) {
            assertThatThrownBy(() -> t.send(SmtpMailTransportTests.message())).isInstanceOf(RetryableJobException.class).hasNoCause();
            assertThat(p.data.get(2, TimeUnit.SECONDS)).isNotEmpty();
        }
    }
    @Test void rawSocketAbortBreaksStartTlsHandshake() throws Exception {
        try (var p = new Peer(Mode.STARTTLS)) {
            var props = SmtpMailTransportTests.settings(p.port());
            props.setProperty("mail.smtp.starttls.enable", "true");
            try (var t = new SmtpMailTransport(props, "", "", Duration.ofMillis(400))) {
                assertThatThrownBy(() -> t.send(SmtpMailTransportTests.message())).hasMessage("SMTP_DEADLINE").hasNoCause();
                assertThat(p.closed.get(2, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
    @Test void blockedDataWriteIsAbortedAndPeerSeesClosure() throws Exception {
        try (var p = new Peer(Mode.BLOCKED_WRITE); var t = transport(p, 400)) {
            var m = SmtpMailTransportTests.message(); m.setText("x".repeat(16 * 1024 * 1024));
            assertThatThrownBy(() -> t.send(m)).hasMessage("SMTP_DEADLINE").hasNoCause();
            assertThat(p.closed.get(3, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void writeTimeoutUsesSharedWatchdogBeforeAbsoluteDeadline() throws Exception {
        try (var p = new Peer(Mode.BLOCKED_WRITE)) {
            var props = SmtpMailTransportTests.settings(p.port());
            props.setProperty("mail.smtp.writetimeout", "200");
            try (var t = new SmtpMailTransport(props, "", "", Duration.ofMillis(1500))) {
                var m = SmtpMailTransportTests.message(); m.setText("x".repeat(16 * 1024 * 1024));
                assertThatThrownBy(() -> t.send(m)).hasMessage("SMTP_TIMEOUT").hasNoCause();
                assertThat(p.closed.get(3, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
    @Test void sameTransportUsesOnlyTwoLanesAndOneWatchdogAcrossRepeatedDeadlines() throws Exception {
        var peers = Executors.newFixedThreadPool(2);
        var lanes = Executors.newFixedThreadPool(2);
        var observer = Executors.newSingleThreadExecutor();
        var sockets = new java.util.concurrent.CopyOnWriteArrayList<Socket>();
        var errors = new CompletableFuture<Throwable>();
        var eof = new CountDownLatch(8);
        try (var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var t = new SmtpMailTransport(SmtpMailTransportTests.settings(server.getLocalPort()), "", "", Duration.ofMillis(220))) {
            observer.submit(() -> {
                try {
                    for (int i = 0; i < 8; i++) {
                        var s = server.accept(); sockets.add(s);
                        peers.submit(() -> { try (s) { s.setSoTimeout(2000); if (s.getInputStream().read() == -1) eof.countDown(); } catch (Throwable e) { errors.complete(e); } });
                    }
                } catch (Throwable e) { errors.complete(e); }
            });
            for (int batch = 0; batch < 4; batch++) {
                var a = lanes.submit(() -> assertThatThrownBy(() -> t.send(SmtpMailTransportTests.message())).hasMessage("SMTP_DEADLINE"));
                var b = lanes.submit(() -> assertThatThrownBy(() -> t.send(SmtpMailTransportTests.message())).hasMessage("SMTP_DEADLINE"));
                a.get(3, TimeUnit.SECONDS); b.get(3, TimeUnit.SECONDS);
                assertThat(Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().startsWith("smtp-watchdog-") && thread.isAlive()).count()).isEqualTo(1);
            }
            assertThat(eof.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(errors.isDone()).isFalse();
        } finally {
            for (var socket : sockets) socket.close();
            observer.shutdownNow(); peers.shutdownNow(); lanes.shutdownNow();
            assertThat(observer.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(peers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(lanes.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void interruptionClosesIoAndFixedLanesSurviveRepeatedTimeouts() throws Exception {
        var lanes = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 6; i++) {
                try (var p = new Peer(Mode.GREETING); var t = transport(p, 300)) {
                    var returned = new CountDownLatch(1);
                    var f = lanes.submit(() -> { try { t.send(SmtpMailTransportTests.message()); } catch (RetryableJobException ignored) { } finally { returned.countDown(); } });
                    assertThat(p.active.await(2, TimeUnit.SECONDS)).isTrue();
                    if (i == 0) f.cancel(true);
                    assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(p.closed.get(2, TimeUnit.SECONDS)).isTrue();
                }
            }
            assertThat(Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("smtp-watchdog-") && t.isAlive()).count()).isLessThanOrEqualTo(1);
        } finally { lanes.shutdownNow(); assertThat(lanes.awaitTermination(3, TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void shutdownAbortsActiveSocketAndCallerReturnsBeforeSlotIsReleased() throws Exception {
        var lane = Executors.newSingleThreadExecutor();
        try (var p = new Peer(Mode.GREETING); var t = transport(p, 1500)) {
            var send = lane.submit(() -> assertThatThrownBy(() -> t.send(SmtpMailTransportTests.message()))
                    .isInstanceOf(RetryableJobException.class).hasNoCause());
            assertThat(p.active.await(2, TimeUnit.SECONDS)).isTrue();
            t.close();
            send.get(2, TimeUnit.SECONDS);
            assertThat(p.closed.get(2, TimeUnit.SECONDS)).isTrue();
        } finally { lane.shutdownNow(); assertThat(lane.awaitTermination(2, TimeUnit.SECONDS)).isTrue(); }
    }
}
