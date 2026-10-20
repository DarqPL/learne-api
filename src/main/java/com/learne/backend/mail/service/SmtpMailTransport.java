package com.learne.backend.mail.service;

import com.learne.backend.jobs.service.*;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.net.SocketFactory;
import org.eclipse.angus.mail.smtp.*;
import org.springframework.mail.SimpleMailMessage;

/** Per-call SMTP connection. Success means DATA acceptance, not inbox delivery.
 * A lost final acknowledgement can cause duplicate delivery on retry.
 * The watchdog closes the raw socket even after STARTTLS wraps it; it never takes
 * SMTPTransport's monitor or performs TLS close-notify. The caller's total deadline
 * includes DNS. Uninterruptible OS DNS can retain one of two fixed, zero-queue IO
 * slots after the caller returns; saturation rejects with BUSY. Aborted calls cannot
 * connect or send when DNS eventually returns. Original host/TLS identity is retained.
 */
public final class SmtpMailTransport implements MailTransport, AutoCloseable {
    private final Properties properties = new Properties();
    private final String username;
    private final String password;
    private final long deadlineNanos;
    private final long writeNanos;
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1, r -> {
        var t = new Thread(r, "smtp-watchdog-1"); t.setDaemon(true); return t;
    });
    private final Set<OwnedSocket> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<String> connectionPreparation;
    private final SmtpDeliveryExecutor deliveries;

    public SmtpMailTransport(Properties supplied, String username, String password, Duration deadline) {
        this(supplied, username, password, deadline, 2, host -> { });
    }

    SmtpMailTransport(Properties supplied, String username, String password, Duration deadline, int slots, Consumer<String> connectionPreparation) {
        SmtpLoggingGuard.install();
        this.connectionPreparation = connectionPreparation;
        if (!"smtp".equals(supplied.getProperty("mail.transport.protocol", "smtp"))
                || Boolean.parseBoolean(supplied.getProperty("mail.smtp.ssl.enable", "false"))) throw permanent("SMTP_CONFIGURATION");
        for (String key : List.of("host", "port", "auth", "starttls.enable", "starttls.required", "connectiontimeout", "timeout", "writetimeout")) {
            String value = supplied.getProperty("mail.smtp." + key);
            if (value != null) properties.setProperty("mail.smtp." + key, value);
        }
        properties.putIfAbsent("mail.smtp.connectiontimeout", "5000");
        properties.putIfAbsent("mail.smtp.timeout", "15000");
        properties.putIfAbsent("mail.smtp.writetimeout", "15000");
        properties.putIfAbsent("mail.smtp.port", "25");
        try {
            if (properties.getProperty("mail.smtp.host", "").isBlank() || deadline == null || deadline.isNegative() || deadline.isZero()) throw new IllegalArgumentException();
            int port = Integer.parseInt(properties.getProperty("mail.smtp.port"));
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            for (String key : List.of("connectiontimeout", "timeout", "writetimeout"))
                if (Integer.parseInt(properties.getProperty("mail.smtp." + key)) <= 0) throw new IllegalArgumentException();
            deadlineNanos = deadline.toNanos();
            writeNanos = TimeUnit.MILLISECONDS.toNanos(Integer.parseInt(properties.getProperty("mail.smtp.writetimeout")));
        } catch (RuntimeException ignored) { throw permanent("SMTP_CONFIGURATION"); }
        this.username = username; this.password = password;
        if (Boolean.parseBoolean(properties.getProperty("mail.smtp.auth")) && (username == null || username.isBlank() || password == null || password.isBlank())) throw permanent("SMTP_CONFIGURATION");
        // Enabling STARTTLS must not silently fall back to cleartext.
        if (Boolean.parseBoolean(properties.getProperty("mail.smtp.starttls.enable"))) properties.setProperty("mail.smtp.starttls.required", "true");
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.quitwait", "false");
        properties.setProperty("mail.smtp.socketFactory.fallback", "false");
        properties.setProperty("mail.debug", "false");
        watchdog.setRemoveOnCancelPolicy(true);
        deliveries = new SmtpDeliveryExecutor(slots);
    }

    /** Defensive copy of non-credential session settings. */
    public Properties sessionProperties() { var copy = new Properties(); copy.putAll(properties); return copy; }

    @Override public void send(SimpleMailMessage source) {
        if (closed.get()) throw permanent("SMTP_CONFIGURATION");
        long started = System.nanoTime();
        var socket = new OwnedSocket();
        deliveries.execute(() -> sendOwned(source, socket, started), socket::abort, started, deadlineNanos);
    }

    private void sendOwned(SimpleMailMessage source, OwnedSocket socket, long started) {
        active.add(socket);
        if (closed.get()) { active.remove(socket); socket.abort(); throw permanent("SMTP_CONFIGURATION"); }
        Thread caller = Thread.currentThread();
        var timedOut = new AtomicBoolean();
        var interrupted = new AtomicBoolean();
        var writeTimedOut = new AtomicBoolean();
        ScheduledFuture<?> timer = null;
        SMTPTransport transport = null;
        try {
            timer = watchdog.scheduleWithFixedDelay(() -> {
                long now = System.nanoTime();
                if (caller.isInterrupted()) { interrupted.set(true); socket.abort(); }
                if (now - started >= deadlineNanos) { timedOut.set(true); socket.abort(); }
                long writing = socket.writing;
                if (writing != 0 && now - writing >= writeNanos) { writeTimedOut.set(true); socket.abort(); }
            }, 0, 10, TimeUnit.MILLISECONDS);
            var p = sessionProperties();
            // Our shared watchdog implements write timeout without Angus per-socket timer threads.
            p.remove("mail.smtp.writetimeout");
            p.put("mail.smtp.socketFactory", new OwnedSocketFactory(socket, () -> {
                // Injectable pre-resolution stage inside Angus's synchronous connect path.
                // Keep the configured hostname intact for STARTTLS/SNI/identity checking.
                connectionPreparation.accept(properties.getProperty("mail.smtp.host"));
                requireLive(socket, started);
            }));
            // Separate safeguards: source JUL filters and disabled debug PrintStream.
            SmtpLoggingGuard.install();
            var session = Session.getInstance(p); session.setDebug(false);
            var mime = render(session, source);
            transport = (SMTPTransport) session.getTransport("smtp");
            requireLive(socket, started);
            transport.connect(properties.getProperty("mail.smtp.host"), Integer.parseInt(properties.getProperty("mail.smtp.port")), username, password);
            requireLive(socket, started);
            transport.sendMessage(mime, mime.getAllRecipients());
            if (interrupted.get() || caller.isInterrupted()) throw retry("SMTP_INTERRUPTED");
            if (timedOut.get()) throw retry("SMTP_DEADLINE");
        } catch (MessagingException e) {
            if (interrupted.get() || caller.isInterrupted()) throw retry("SMTP_INTERRUPTED");
            if (timedOut.get()) throw retry("SMTP_DEADLINE");
            if (writeTimedOut.get()) throw retry("SMTP_TIMEOUT");
            throw classify(e, transport == null ? 0 : transport.getLastReturnCode());
        } finally {
            // Abort first: neither QUIT nor TLS shutdown may extend cleanup behind blocked IO.
            socket.abort();
            try { if (transport != null) transport.close(); } catch (MessagingException ignored) { }
            finally {
                if (timer != null) timer.cancel(false);
                active.remove(socket);
                if (closed.get() && active.isEmpty()) watchdog.shutdown();
            }
        }
    }

    private void requireLive(OwnedSocket socket, long started) {
        if (System.nanoTime() - started >= deadlineNanos) { socket.abort(); throw retry("SMTP_DEADLINE"); }
        if (closed.get() || socket.isClosed() || Thread.currentThread().isInterrupted()) {
            socket.abort(); throw retry("SMTP_INTERRUPTED");
        }
    }

    private static MimeMessage render(Session session, SimpleMailMessage source) throws MessagingException {
        if (source == null || source.getTo() == null || source.getTo().length != 1
                || source.getCc() != null && source.getCc().length > 0 || source.getBcc() != null && source.getBcc().length > 0)
            throw permanent("SMTP_INVALID_ADDRESS");
        var mime = new MimeMessage(session);
        mime.setFrom(address(source.getFrom()));
        mime.setRecipient(Message.RecipientType.TO, address(source.getTo()[0]));
        if (source.getReplyTo() != null) mime.setReplyTo(new Address[]{address(source.getReplyTo())});
        String subject = source.getSubject();
        if (subject != null && (subject.contains("\r") || subject.contains("\n"))) throw permanent("SMTP_INVALID_ADDRESS");
        mime.setSubject(subject == null ? "" : subject, "UTF-8");
        mime.setText(source.getText() == null ? "" : source.getText(), "UTF-8");
        mime.setSentDate(source.getSentDate() == null ? new Date() : source.getSentDate());
        mime.saveChanges(); return mime;
    }
    private static InternetAddress address(String value) {
        try {
            if (value == null || value.contains("\r") || value.contains("\n")) throw new AddressException();
            var parsed = InternetAddress.parse(value, true);
            if (parsed.length != 1 || parsed[0].isGroup()) throw new AddressException();
            parsed[0].validate(); return parsed[0];
        } catch (AddressException ignored) { throw permanent("SMTP_INVALID_ADDRESS"); }
    }
    private static RuntimeException classify(MessagingException failure, int lastCode) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>(); pending.add(failure);
        boolean auth = false, io = false, timeout = false, invalid = false;
        int code = 0;
        while (!pending.isEmpty()) {
            var e = pending.remove(); if (!seen.add(e)) continue;
            if (e instanceof SMTPAddressFailedException smtp) code = smtp.getReturnCode();
            if (e instanceof SMTPSendFailedException smtp) code = smtp.getReturnCode();
            auth |= e instanceof AuthenticationFailedException;
            invalid |= e instanceof AddressException;
            timeout |= e instanceof SocketTimeoutException;
            io |= e instanceof IOException;
            if (e.getCause() != null) pending.add(e.getCause());
            if (e instanceof MessagingException mail && mail.getNextException() != null) pending.add(mail.getNextException());
        }
        if (code == 0) code = lastCode;
        if (code >= 400 && code < 500) return retry("SMTP_TRANSIENT");
        if (auth) return permanent("SMTP_AUTHENTICATION");
        if (code >= 500 && code < 600) return permanent("SMTP_REJECTED");
        if (invalid) return permanent("SMTP_INVALID_ADDRESS");
        if (timeout) return retry("SMTP_TIMEOUT");
        // Angus reports EOF (including loss of the final DATA acknowledgement) as
        // return code -1, without necessarily retaining an IOException cause.
        if (code == -1) return retry("SMTP_CONNECTION");
        if (io) return retry("SMTP_CONNECTION");
        return permanent("SMTP_CONFIGURATION");
    }
    private static PermanentJobException permanent(String code) { return new PermanentJobException(code); }
    private static RetryableJobException retry(String code) { return new RetryableJobException(code, null); }
    @Override public void close() {
        closed.set(true);
        deliveries.close();
        active.forEach(OwnedSocket::abort);
        // Active sends retain their watchdog until transport cleanup has returned.
        if (active.isEmpty()) watchdog.shutdown();
    }

    private static final class OwnedSocket extends Socket {
        volatile long writing;
        void abort() { try { close(); } catch (IOException ignored) { } }
        @Override public OutputStream getOutputStream() throws IOException {
            var delegate = super.getOutputStream();
            return new FilterOutputStream(delegate) {
                @Override public void write(int b) throws IOException { writing = System.nanoTime(); try { out.write(b); } finally { writing = 0; } }
                @Override public void write(byte[] b, int off, int len) throws IOException { writing = System.nanoTime(); try { out.write(b, off, len); } finally { writing = 0; } }
            };
        }
    }
    private static final class OwnedSocketFactory extends SocketFactory {
        private final OwnedSocket socket;
        private final Runnable prepare;
        OwnedSocketFactory(OwnedSocket socket, Runnable prepare) { this.socket = socket; this.prepare = prepare; }
        @Override public Socket createSocket() { prepare.run(); return socket; }
        @Override public Socket createSocket(String h, int p) throws IOException { prepare.run(); socket.connect(new InetSocketAddress(h, p)); return socket; }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { return createSocket(h.getHostAddress(), p); }
        @Override public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { socket.bind(new InetSocketAddress(l, lp)); return createSocket(h, p); }
        @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { return createSocket(h.getHostAddress(), p, l, lp); }
    }
}
