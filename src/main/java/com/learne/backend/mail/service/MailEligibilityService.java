package com.learne.backend.mail.service;

import com.learne.backend.jobs.service.JobErrorCode;
import com.learne.backend.jobs.service.RetryableJobException;
import com.learne.backend.mail.model.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only, fresh eligibility per attempt. No queue mutation, transport, or domain side effects. */
public final class MailEligibilityService {
    public enum Result {
        ALLOW, RECIPIENT_UNAVAILABLE, RECIPIENT_EMAIL_CHANGED, VERIFICATION_ATTEMPTS_EXHAUSTED,
        GENERATION_STALE, CODE_EXPIRED, REVIEW_NO_LONGER_PENDING, ORDER_NOT_PAID
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate read;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public MailEligibilityService(JdbcTemplate jdbc, PlatformTransactionManager manager, PasswordEncoder passwords, Clock clock) {
        this.jdbc = jdbc; this.passwords = passwords; this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setTimeout(5);
    }

    public Result check(MailPayload payload) {
        MailPayloadCodec.validate(payload);
        if (expired(payload)) return Result.CODE_EXPIRED;
        return inRead(() -> checkCurrent(payload));
    }

    /** Operator reader: same authoritative rules, in the caller's domain-before-job lock scope. */
    public Result checkNoticeInCurrentTransaction(MailPayload payload) {
        MailPayloadCodec.validate(payload);
        if (MailPayloadCodec.requiresSecret(payload.template())
                || !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalArgumentException("Notice transaction required");
        return checkCurrent(payload);
    }

    /** Rechecks all invitation state after decryption, then compares with the current hash. */
    public Result checkInvitationPassword(MailPayload payload, byte[] secret) {
        MailPayloadCodec.validate(payload);
        if (payload.template() != MailTemplate.TEACHER_INVITATION || secret == null || secret.length == 0 || secret.length > 4096)
            throw new IllegalArgumentException("Invalid invitation secret");
        if (expired(payload)) return Result.CODE_EXPIRED;
        return inRead(() -> {
            Result current = checkCurrent(payload);
            if (current != Result.ALLOW) return current;
            var row = one("""
                    SELECT password_hash FROM users WHERE id=? AND invitation_generation=?
                    AND must_change_password=true AND is_active=true AND is_locked=false AND role='TEACHER'
                    AND lower(trim(email))=?
                    """, payload.recipientUserId(), UUID.fromString(payload.generation()), normalize(payload.recipientEmail()));
            if (row == null) return Result.GENERATION_STALE;
            CharBuffer plaintext = null;
            try {
                plaintext = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(secret));
                return passwords.matches(plaintext, (String) row.get("password_hash")) ? Result.ALLOW : Result.GENERATION_STALE;
            } catch (CharacterCodingException ignored) { return Result.GENERATION_STALE; }
            finally {
                if (plaintext != null) {
                    plaintext.clear();
                    while (plaintext.hasRemaining()) plaintext.put('\0');
                }
            }
        });
    }

    private Result inRead(Supplier<Result> check) {
        try { return read.execute(status -> check.get()); }
        catch (DataAccessException | TransactionException ignored) {
            // Never retain driver SQL diagnostics, recipient data, or password hashes.
            throw new RetryableJobException(JobErrorCode.MAIL_ELIGIBILITY_UNAVAILABLE.name(), null);
        }
    }

    private boolean expired(MailPayload p) {
        return MailPayloadCodec.requiresSecret(p.template()) && p.expiresAt().isBefore(clock.instant().plusSeconds(30));
    }

    private Result checkCurrent(MailPayload p) {
        if (expired(p)) return Result.CODE_EXPIRED;
        if (p.template() == MailTemplate.REGISTRATION_OTP) return registration(p);
        var user = one("""
                SELECT id,email,role,is_active,is_locked,must_change_password,invitation_generation
                FROM users WHERE id=?
                """, p.recipientUserId());
        if (user == null) return Result.RECIPIENT_UNAVAILABLE;
        if (!normalize((String) user.get("email")).equals(normalize(p.recipientEmail()))) return Result.RECIPIENT_EMAIL_CHANGED;
        return switch (p.template()) {
            case PASSWORD_RESET -> {
                if (!activeUnlocked(user)) yield Result.RECIPIENT_UNAVAILABLE;
                // EXACT ordering used by reset confirmation's locking JPQL query.
                var token = one("""
                        SELECT id,expires_at,attempts FROM password_reset_tokens
                        WHERE user_id=? AND used_at IS NULL ORDER BY created_at DESC,id DESC LIMIT 1
                        """, p.recipientUserId());
                if (token == null || !p.generation().equals(token.get("id").toString())) yield Result.GENERATION_STALE;
                yield verification(token);
            }
            case TEACHER_INVITATION -> {
                if (!"TEACHER".equals(user.get("role")) || !activeUnlocked(user)) yield Result.RECIPIENT_UNAVAILABLE;
                if (!Boolean.TRUE.equals(user.get("must_change_password"))
                        || !p.generation().equals(Objects.toString(user.get("invitation_generation"), null))) yield Result.GENERATION_STALE;
                yield Result.ALLOW;
            }
            case PAYMENT_CONFIRMATION -> {
                var order = one("SELECT user_id,status FROM orders WHERE id=?", p.orderId());
                if (order == null || ((Number) order.get("user_id")).longValue() != p.recipientUserId()) yield Result.RECIPIENT_UNAVAILABLE;
                yield "PAID".equals(order.get("status")) ? Result.ALLOW : Result.ORDER_NOT_PAID;
            }
            case COURSE_PENDING_REVIEW -> {
                // An administrator lock blocks login, not an active administrator's review notices.
                if (!"ADMIN".equals(user.get("role")) || !Boolean.TRUE.equals(user.get("is_active"))) yield Result.RECIPIENT_UNAVAILABLE;
                var revision = one("""
                        SELECT id,status FROM course_revisions WHERE course_id=? ORDER BY revision_number DESC LIMIT 1
                        """, p.courseId());
                yield revision != null && ((Number) revision.get("id")).longValue() == p.revisionId()
                        && "PENDING_REVIEW".equals(revision.get("status")) ? Result.ALLOW : Result.REVIEW_NO_LONGER_PENDING;
            }
            case COURSE_REVIEW_RESULT -> {
                if (!"TEACHER".equals(user.get("role"))) yield Result.RECIPIENT_UNAVAILABLE;
                yield historicalReview(p);
            }
            default -> throw new IllegalArgumentException("Invalid mail template");
        };
    }

    private Result registration(MailPayload p) {
        var pending = one("SELECT mail_generation,expires_at,attempts FROM registration_otps WHERE lower(trim(email))=?", normalize(p.recipientEmail()));
        if (pending == null || !p.generation().equals(Objects.toString(pending.get("mail_generation"), null))) return Result.GENERATION_STALE;
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE lower(trim(email))=?)", Boolean.class, normalize(p.recipientEmail()))))
            return Result.RECIPIENT_UNAVAILABLE;
        return verification(pending);
    }

    private Result verification(Map<String, Object> row) {
        if (((Number) row.get("attempts")).intValue() >= 5) return Result.VERIFICATION_ATTEMPTS_EXHAUSTED;
        return instant(row.get("expires_at")).isBefore(clock.instant().plusSeconds(30)) ? Result.CODE_EXPIRED : Result.ALLOW;
    }

    private Result historicalReview(MailPayload p) {
        var decision = one("SELECT * FROM course_review_decisions WHERE id=?", p.decisionId());
        if (decision == null || ((Number) decision.get("teacher_id")).longValue() != p.recipientUserId()
                || ((Number) decision.get("course_id")).longValue() != p.courseId()
                || ((Number) decision.get("revision_id")).longValue() != p.revisionId()
                || !Objects.equals(decision.get("submission_id"), p.submissionId())
                || !normalize((String) decision.get("recipient_email")).equals(normalize(p.recipientEmail()))
                || !Objects.equals(decision.get("course_name"), p.values().get("courseName"))
                || !Objects.equals(decision.get("decision"), p.values().get("decision"))
                || !Objects.toString(decision.get("rejection_reason"), "").equals(p.values().get("rejectionReason"))
                || !instant(decision.get("reviewed_at")).equals(Instant.parse(p.values().get("reviewedAt")))) return Result.GENERATION_STALE;
        return Result.ALLOW;
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static boolean activeUnlocked(Map<String, Object> user) {
        return Boolean.TRUE.equals(user.get("is_active")) && !Boolean.TRUE.equals(user.get("is_locked"));
    }
    private static String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof OffsetDateTime date) return date.toInstant();
        return (Instant) value;
    }
}
