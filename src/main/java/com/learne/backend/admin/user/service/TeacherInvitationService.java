package com.learne.backend.admin.user.service;

import com.learne.backend.admin.user.dto.InvitationDeliveryResponse;
import com.learne.backend.auth.session.service.RefreshTokenService;
import com.learne.backend.domain.auth.*;
import com.learne.backend.jobs.service.JobErrorCode;
import com.learne.backend.shared.api.ApiException;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherInvitationService {
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TemporaryPasswordGenerator generator;
    private final TeacherInvitationMailService mail;
    private final RefreshTokenService refresh;

    public TeacherInvitationService(UserRepository users, JdbcTemplate jdbc, PasswordEncoder passwords,
            TemporaryPasswordGenerator generator, TeacherInvitationMailService mail, RefreshTokenService refresh) {
        this.users = users; this.jdbc = jdbc; this.passwords = passwords;
        this.generator = generator; this.mail = mail; this.refresh = refresh;
    }

    @Transactional
    public InvitationDeliveryResponse resend(long teacherId) {
        User user = users.findByIdForUpdate(teacherId)
                .filter(u -> u.getRole() == UserRole.TEACHER)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Teacher not found."));
        if (!eligible(user)) throw new ApiException(HttpStatus.CONFLICT, "Teacher invitation is not eligible for resend.");
        Instant now = Instant.now();
        if (user.getInvitationGeneration() != null) {
            var rows = jdbc.queryForList("""
                    SELECT state,expires_at FROM background_jobs
                    WHERE type='TEACHER_INVITATION_MAIL' AND payload->>'recipientUserId'=?
                    AND payload->>'generation'=? FOR UPDATE
                    """, Long.toString(teacherId), user.getInvitationGeneration().toString());
            if (rows.stream().anyMatch(row -> pending((String) row.get("state"))
                    && !expired(instant(row.get("expires_at")), now)))
                throw new ApiException(HttpStatus.CONFLICT, "Teacher invitation is already pending.");
        }
        String password = generator.generate();
        UUID generation = UUID.randomUUID();
        user.setPasswordHash(passwords.encode(password));
        user.setInvitationGeneration(generation);
        refresh.revokeAllActiveTokens(user);
        mail.sendTeacherInvitation(user, password, generation, now.plus(Duration.ofHours(24)));
        return new InvitationDeliveryResponse("QUEUED", now, null, null, false);
    }

    @Transactional(readOnly = true)
    public Map<Long, InvitationDeliveryResponse> deliveryByTeacherIds(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        var result = new HashMap<Long, InvitationDeliveryResponse>();
        Instant now = Instant.now();
        new NamedParameterJdbcTemplate(jdbc).query("""
                SELECT u.id,u.invitation_generation,u.is_active,u.is_locked,u.must_change_password,
                       j.id AS job_id,j.state,j.created_at,j.completed_at,j.expires_at,j.last_error_code
                FROM users u LEFT JOIN LATERAL (
                    SELECT id,state,created_at,completed_at,expires_at,last_error_code FROM background_jobs
                    WHERE type='TEACHER_INVITATION_MAIL' AND payload->>'recipientUserId'=u.id::text
                    AND payload->>'generation'=u.invitation_generation::text
                    ORDER BY created_at DESC,id DESC LIMIT 1
                ) j ON true WHERE u.id IN (:ids) AND u.role='TEACHER'
                """, Map.of("ids", ids), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            String state = rs.getString("state");
            String status = rs.getObject("invitation_generation") == null ? "NOT_REQUESTED"
                    : rs.getObject("job_id") == null ? "HISTORY_UNAVAILABLE" : switch (state) {
                        case "READY" -> "QUEUED"; case "RUNNING" -> "SENDING";
                        case "RETRY_WAIT" -> "RETRYING"; case "SUCCEEDED" -> "SENT";
                        case "DEAD" -> "FAILED"; case "SKIPPED" -> "SKIPPED";
                        default -> throw new IllegalStateException("Invalid invitation state");
                    };
            boolean eligible = rs.getBoolean("is_active") && !rs.getBoolean("is_locked") && rs.getBoolean("must_change_password");
            result.put(rs.getLong("id"), new InvitationDeliveryResponse(status,
                    instant(rs.getObject("created_at")), instant(rs.getObject("completed_at")),
                    publicCode(rs.getString("last_error_code")), eligible && (!pending(state) || expired(instant(rs.getObject("expires_at")), now))));
        });
        return Map.copyOf(result);
    }

    private static boolean eligible(User u) { return u.isActive() && !u.isLocked() && u.isMustChangePassword(); }
    private static boolean pending(String state) { return Set.of("READY", "RUNNING", "RETRY_WAIT").contains(state == null ? "" : state); }
    private static boolean expired(Instant deadline, Instant now) { return deadline != null && !deadline.isAfter(now); }
    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof OffsetDateTime t) return t.toInstant();
        return (Instant) value;
    }
    private static String publicCode(String code) {
        if (code == null) return null;
        try { return JobErrorCode.valueOf(code).name(); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
