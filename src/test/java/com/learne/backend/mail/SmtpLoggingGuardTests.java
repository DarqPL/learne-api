package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.mail.service.SmtpMailTransport;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SmtpLoggingGuardTests {
    static final String OTP = "LOG_TEST_OTP_827461";
    static final String PASSWORD = "LOG_TEST_PASSWORD_49fc";
    static final String RESPONSE = "LOG_TEST_PROVIDER_SECRET_f3dd";
    static final List<String> SOURCES = List.of("org.eclipse.angus.mail.smtp", "org.eclipse.angus.mail.smtp.protocol",
            "org.eclipse.angus.mail.util.socket", "org.eclipse.angus.mail.auth");
    static final class Capture implements AutoCloseable {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        final Map<Logger, Level> levels = new HashMap<>();
        final Map<Logger, Filter> filters = new HashMap<>();
        final Map<Logger, Boolean> parents = new HashMap<>();
        final Handler handler = new Handler() {
            public void publish(LogRecord r) { records.add(r); }
            public void flush() { }
            public void close() { }
        };
        Capture() {
            handler.setLevel(Level.ALL);
            for (String name : SOURCES) attach(name);
            attach("com.learne.backend.mail.safe-test");
            Logger.getLogger("").addHandler(handler);
        }
        void attach(String name) {
            var logger = Logger.getLogger(name); levels.put(logger, logger.getLevel()); filters.put(logger, logger.getFilter());
            parents.put(logger, logger.getUseParentHandlers());
            logger.setLevel(Level.FINEST); logger.setUseParentHandlers(true); logger.addHandler(handler);
        }
        void assertSafeBoundary() {
            Logger.getLogger("com.learne.backend.mail.safe-test").fine("SMTP_TRANSIENT");
            assertThat(records.stream().anyMatch(r -> "SMTP_TRANSIENT".equals(r.getMessage()))).isTrue();
            String captured = records.stream().map(r -> {
                var text = new java.io.StringWriter();
                if (r.getThrown() != null) r.getThrown().printStackTrace(new java.io.PrintWriter(text));
                return r.getMessage() + Arrays.toString(r.getParameters()) + text;
            }).collect(java.util.stream.Collectors.joining("\n"));
            assertThat(captured).doesNotContain(OTP, PASSWORD, RESPONSE, "log-sensitive-recipient@example.test", "private mail subject");
            assertThat(records.stream().filter(r -> SOURCES.contains(r.getLoggerName())).toList()).isEmpty();
        }
        public void close() {
            Logger.getLogger("").removeHandler(handler);
            levels.forEach((l, level) -> { l.removeHandler(handler); l.setLevel(level); l.setFilter(filters.get(l)); l.setUseParentHandlers(parents.get(l)); });
        }
    }
    SmtpMailTransport transport(SmtpDeadlineIT.Peer peer) {
        return new SmtpMailTransport(SmtpMailTransportTests.settings(peer.port()), "", "", Duration.ofMillis(1500));
    }
    org.springframework.mail.SimpleMailMessage sensitiveMessage() {
        var m = SmtpMailTransportTests.message(); m.setTo("log-sensitive-recipient@example.test");
        m.setSubject("private mail subject"); m.setText(OTP + " " + PASSWORD); return m;
    }
    @Test void finestJulCannotPublishRecipientOrDataEvenWithSessionDebugFalse() throws Exception {
        try (var capture = new Capture(); var p = new SmtpDeadlineIT.Peer(SmtpDeadlineIT.Mode.SUCCESS); var t = transport(p)) {
            assertThat(t.sessionProperties().getProperty("mail.debug")).isEqualTo("false");
            t.send(sensitiveMessage());
            assertThat(new String(p.data.get(2, TimeUnit.SECONDS), java.nio.charset.StandardCharsets.UTF_8)).contains(OTP, PASSWORD);
            capture.assertSafeBoundary();
        }
    }
    @Test void providerRejectionCannotPublishResponseOrRawExceptionBeforeSanitizer() throws Exception {
        try (var capture = new Capture(); var p = new SmtpDeadlineIT.Peer(SmtpDeadlineIT.Mode.REJECT550, RESPONSE); var t = transport(p)) {
            assertThatThrownBy(() -> t.send(sensitiveMessage())).isInstanceOf(PermanentJobException.class).hasMessage("SMTP_REJECTED").hasNoCause();
            capture.assertSafeBoundary();
        }
    }
    @Test void closingOneInstanceDoesNotExposeAnotherInflightProtocolTrace() throws Exception {
        var callers = Executors.newFixedThreadPool(2);
        try (var capture = new Capture();
             var p1 = new SmtpDeadlineIT.Peer(SmtpDeadlineIT.Mode.GREETING);
             var p2 = new SmtpDeadlineIT.Peer(SmtpDeadlineIT.Mode.PAUSED_SUCCESS);
             var t1 = transport(p1); var t2 = transport(p2)) {
            var a = callers.submit(() -> assertThatThrownBy(() -> t1.send(sensitiveMessage())).isInstanceOf(RetryableJobException.class));
            var b = callers.submit(() -> t2.send(sensitiveMessage()));
            assertThat(p1.active.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(p2.active.await(1, TimeUnit.SECONDS)).isTrue();
            t1.close(); a.get(1, TimeUnit.SECONDS);
            p2.resume.countDown(); b.get(2, TimeUnit.SECONDS);
            capture.assertSafeBoundary();
        } finally { callers.shutdownNow(); assertThat(callers.awaitTermination(2, TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void everyExactDiagnosticSourceFiltersParametersAndThrowablesAtSource() throws Exception {
        try (var capture = new Capture(); var p = new SmtpDeadlineIT.Peer(SmtpDeadlineIT.Mode.SUCCESS); var t = transport(p)) {
            for (String name : SOURCES) {
                Logger.getLogger(name).log(Level.FINEST, "private {0}", new Object[]{OTP, PASSWORD});
                Logger.getLogger(name).log(Level.WARNING, "provider diagnostic", new IllegalStateException(RESPONSE));
            }
            capture.assertSafeBoundary();
        }
    }
}
