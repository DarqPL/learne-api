package com.learne.backend.mail.service;

import java.util.List;
import java.util.logging.Filter;
import java.util.logging.Logger;

/**
 * Provider-scoped logging policy for Angus 2.0.5: suppress SMTP wire, response,
 * authentication and socket diagnostics at their JUL source, at every level.
 * MailLogger(Class, ...) uses the package name; SMTPTransport adds ".protocol",
 * SocketFetcher adds ".socket" to the util package, and NTLM uses the auth package.
 * These records can contain DATA credentials, addresses, parameters and raw causes;
 * content-based redaction is therefore deliberately not attempted.
 *
 * JUL parent filters do not filter child records. Each actual source needs its own
 * filter, before direct or inherited handlers see it. Session.debug=false only
 * disables the separate debug PrintStream path, not JUL tracing.
 *
 * This narrow process/classloader-lifetime policy is intentionally never undone by
 * transport shutdown: another instance, cleanup or an uninterruptible DNS task may
 * still be running. Strong references prevent JUL's weak logger registry from
 * collecting the protected loggers. Application logging/root handlers are untouched;
 * operational diagnostics must use the application's finite safe codes/metrics.
 * Re-review this exact source list when upgrading the mail provider.
 */
final class SmtpLoggingGuard {
    private static final Filter SUPPRESS = record -> false;
    private static final List<Logger> SOURCES = List.of(
            Logger.getLogger("org.eclipse.angus.mail.smtp"),
            Logger.getLogger("org.eclipse.angus.mail.smtp.protocol"),
            Logger.getLogger("org.eclipse.angus.mail.util.socket"),
            Logger.getLogger("org.eclipse.angus.mail.auth"));

    private SmtpLoggingGuard() { }

    static synchronized void install() {
        // Do not invoke previous filters: they too could publish the sensitive record.
        for (var logger : SOURCES) logger.setFilter(SUPPRESS);
    }
}
