package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.domain.auth.*;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.MailEligibilityService;
import com.learne.backend.mail.service.MailEligibilityService.Result;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class MailEligibilityIT extends MailPostgresTestSupport {
    @Autowired PasswordResetTokenRepository tokens;
    @Autowired UserRepository users;
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    MailEligibilityService eligibility;
    static final Instant NOW = MailEligibilityTests.NOW;
    static final String GEN = MailEligibilityTests.GENERATION;

    @BeforeEach void eligibility() {
        eligibility = new MailEligibilityService(jdbc, manager, encoder, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    void user(long id, String role) {
        jdbc.update("insert into users(id,email,password_hash,full_name,role) values(?,?,?,?,?)",
                id, id == 7 ? "student@example.test" : "u" + id + "@example.test", encoder.encode("temporary-password"), "Recipient", role);
    }
    MailPayload auth(MailTemplate template) { return MailEligibilityTests.auth(template, 3600); }
    void reset(long id) {
        jdbc.update("insert into password_reset_tokens(id,user_id,token_hash,expires_at,created_at) values(?,7,?,?,?)",
                id, "hash-" + id, Timestamp.from(NOW.plusSeconds(3600)), Timestamp.from(NOW));
    }
    void registration() {
        jdbc.update("insert into registration_otps(email,full_name,password_hash,otp_hash,expires_at,mail_generation) values(?,?,?,?,?,?)",
                "student@example.test", "Student", "hash", "otp-hash", Timestamp.from(NOW.plusSeconds(3600)), UUID.fromString(GEN));
    }
    MailPayload notice(MailTemplate template) {
        Map<String, String> values = switch (template) {
            case PAYMENT_CONFIRMATION -> Map.of("amountVnd", "100", "itemDescription", "Item", "paidAt", NOW.toString());
            case COURSE_PENDING_REVIEW -> Map.of("courseName", "Snapshot course", "teacherName", "Teacher", "submittedAt", NOW.toString());
            case COURSE_REVIEW_RESULT -> Map.of("courseName", "Snapshot course", "decision", "REJECTED", "rejectionReason", "Historical reason", "reviewedAt", NOW.toString());
            default -> throw new IllegalArgumentException();
        };
        return new MailPayload(7, "student@example.test", "Recipient", template, null, null,
                template == MailTemplate.PAYMENT_CONFIRMATION ? null : 11L,
                template == MailTemplate.PAYMENT_CONFIRMATION ? null : 12L,
                template == MailTemplate.PAYMENT_CONFIRMATION ? null : UUID.fromString(GEN),
                template == MailTemplate.COURSE_REVIEW_RESULT ? UUID.fromString("22222222-2222-2222-2222-222222222222") : null,
                template == MailTemplate.PAYMENT_CONFIRMATION ? 13L : null, values);
    }
    void course() {
        user(8, "TEACHER");
        jdbc.update("insert into courses(id,teacher_id,name,type,level,status) values(11,8,'Current course','GENERAL','A1','DRAFT')");
        jdbc.update("insert into course_revisions(id,course_id,teacher_id,revision_number,status,name,type,level) values(12,11,8,1,'PENDING_REVIEW','Current course','GENERAL','A1')");
    }

    @Test void registrationCurrentReplacedExhaustedExpiredAndAlreadyRegistered() {
        registration();
        var p = auth(MailTemplate.REGISTRATION_OTP);
        assertThat(eligibility.check(p)).isEqualTo(Result.ALLOW);
        jdbc.update("update registration_otps set attempts=5");
        assertThat(eligibility.check(p)).isEqualTo(Result.VERIFICATION_ATTEMPTS_EXHAUSTED);
        jdbc.update("update registration_otps set attempts=4,expires_at=?", Timestamp.from(NOW.plusSeconds(29)));
        assertThat(eligibility.check(p)).isEqualTo(Result.CODE_EXPIRED);
        jdbc.update("update registration_otps set expires_at=?,mail_generation=?", Timestamp.from(NOW.plusSeconds(3600)), UUID.randomUUID());
        assertThat(eligibility.check(p)).isEqualTo(Result.GENERATION_STALE);
        jdbc.update("update registration_otps set mail_generation=?", UUID.fromString(GEN));
        user(7, "STUDENT");
        assertThat(eligibility.check(p)).isEqualTo(Result.RECIPIENT_UNAVAILABLE);
    }

    @Test void resetEqualTimestampsConfirmationAndWorkerSelectSameHighestId() {
        user(7, "STUDENT"); reset(41); reset(42);
        Long confirmationId = new TransactionTemplate(manager).execute(status -> tokens.findLatestUnusedByUserForUpdate(
                users.findById(7L).orElseThrow(), PageRequest.of(0, 1)).getFirst().getId());
        assertThat(confirmationId).isEqualTo(42L);
        assertThat(tokens.findTopByUserAndUsedAtIsNullOrderByCreatedAtDesc(users.findById(7L).orElseThrow()).orElseThrow().getId()).isEqualTo(42L);
        assertThat(eligibility.check(auth(MailTemplate.PASSWORD_RESET))).isEqualTo(Result.ALLOW);
        reset(43);
        assertThat(eligibility.check(auth(MailTemplate.PASSWORD_RESET))).isEqualTo(Result.GENERATION_STALE);
        jdbc.update("update password_reset_tokens set used_at=? where id=43", Timestamp.from(NOW));
        assertThat(eligibility.check(auth(MailTemplate.PASSWORD_RESET))).isEqualTo(Result.ALLOW);
        jdbc.update("update password_reset_tokens set used_at=? where id=42", Timestamp.from(NOW));
        assertThat(eligibility.check(auth(MailTemplate.PASSWORD_RESET))).isEqualTo(Result.GENERATION_STALE);
    }

    @Test void resetAccountsExpiryAndAttemptsAreRechecked() {
        user(7, "STUDENT"); reset(42);
        var p = auth(MailTemplate.PASSWORD_RESET);
        assertThat(eligibility.check(p)).isEqualTo(Result.ALLOW);
        jdbc.update("update users set is_locked=true where id=7");
        assertThat(eligibility.check(p)).isEqualTo(Result.RECIPIENT_UNAVAILABLE);
        jdbc.update("update users set is_locked=false,is_active=false where id=7");
        assertThat(eligibility.check(p)).isEqualTo(Result.RECIPIENT_UNAVAILABLE);
        jdbc.update("update users set is_active=true where id=7");
        jdbc.update("update password_reset_tokens set attempts=5");
        assertThat(eligibility.check(p)).isEqualTo(Result.VERIFICATION_ATTEMPTS_EXHAUSTED);
        jdbc.update("update password_reset_tokens set attempts=4,expires_at=?", Timestamp.from(NOW.plusSeconds(29)));
        assertThat(eligibility.check(p)).isEqualTo(Result.CODE_EXPIRED);
    }

    @Test void invitationRequiresCurrentGenerationAndPasswordAndEligibleTeacher() {
        user(7, "TEACHER");
        jdbc.update("update users set invitation_generation=?,must_change_password=true where id=7", UUID.fromString(GEN));
        var p = auth(MailTemplate.TEACHER_INVITATION);
        assertThat(eligibility.check(p)).isEqualTo(Result.ALLOW);
        byte[] password = "temporary-password".getBytes(StandardCharsets.UTF_8);
        assertThat(eligibility.checkInvitationPassword(p, password)).isEqualTo(Result.ALLOW);
        assertThat(eligibility.checkInvitationPassword(p, "wrong".getBytes(StandardCharsets.UTF_8))).isEqualTo(Result.GENERATION_STALE);
        assertThat(password).isEqualTo("temporary-password".getBytes(StandardCharsets.UTF_8));
        jdbc.update("update users set password_hash=? where id=7", encoder.encode("rotated-password"));
        assertThat(eligibility.checkInvitationPassword(p, password)).isEqualTo(Result.GENERATION_STALE);
        jdbc.update("update users set invitation_generation=? where id=7", UUID.randomUUID());
        assertThat(eligibility.checkInvitationPassword(p, password)).isEqualTo(Result.GENERATION_STALE);
        jdbc.update("update users set invitation_generation=?,must_change_password=false where id=7", UUID.fromString(GEN));
        assertThat(eligibility.check(p)).isEqualTo(Result.GENERATION_STALE);
        jdbc.update("update users set must_change_password=true,is_locked=true where id=7");
        assertThat(eligibility.check(p)).isEqualTo(Result.RECIPIENT_UNAVAILABLE);
    }

    @Test void paymentRequiresStableRecipientAndPaidOrderOwnerWithoutChangingOrder() {
        var p = notice(MailTemplate.PAYMENT_CONFIRMATION);
        assertThat(eligibility.check(p)).isEqualTo(Result.RECIPIENT_UNAVAILABLE);
        user(7, "STUDENT");
        jdbc.update("insert into orders(id,user_id,order_type,status,original_amount_vnd,final_amount_vnd,payment_provider,expires_at) values(13,7,'COURSE_PURCHASE','PAID',100,100,'INTERNAL',?)", Timestamp.from(NOW));
        assertThat(eligibility.check(p)).isEqualTo(Result.ALLOW);
        jdbc.update("update users set email=' STUDENT@EXAMPLE.TEST ' where id=7");
        assertThat(eligibility.check(p)).isEqualTo(Result.ALLOW);
        jdbc.update("update users set email='changed@example.test' where id=7");
        assertThat(eligibility.check(p)).isEqualTo(Result.RECIPIENT_EMAIL_CHANGED);
        jdbc.update("update users set email='student@example.test' where id=7");
        jdbc.update("update orders set status='REFUNDED' where id=13");
        assertThat(eligibility.check(p)).isEqualTo(Result.ORDER_NOT_PAID);
        assertThat(jdbc.queryForObject("select status from orders where id=13", String.class)).isEqualTo("REFUNDED");
        user(9, "STUDENT"); jdbc.update("update orders set user_id=9,status='PAID' where id=13");
        assertThat(eligibility.check(p)).isEqualTo(Result.RECIPIENT_UNAVAILABLE);
    }

    @Test void pendingAllowsLockedActiveAdminButNotInactiveWrongRoleOrSupersededRevision() {
        user(7, "ADMIN"); course();
        var p = notice(MailTemplate.COURSE_PENDING_REVIEW);
        jdbc.update("update users set is_locked=true where id=7");
        assertThat(eligibility.check(p)).isEqualTo(Result.ALLOW);
        jdbc.update("update users set is_active=false where id=7");
        assertThat(eligibility.check(p)).isEqualTo(Result.RECIPIENT_UNAVAILABLE);
        jdbc.update("update users set is_active=true,role='TEACHER' where id=7");
        assertThat(eligibility.check(p)).isEqualTo(Result.RECIPIENT_UNAVAILABLE);
        jdbc.update("update users set role='ADMIN' where id=7");
        jdbc.update("update course_revisions set status='APPROVED' where id=12");
        assertThat(eligibility.check(p)).isEqualTo(Result.REVIEW_NO_LONGER_PENDING);
        jdbc.update("update course_revisions set status='PENDING_REVIEW' where id=12");
        jdbc.update("insert into course_revisions(id,course_id,teacher_id,revision_number,status,name,type,level) values(14,11,8,2,'DRAFT','New','GENERAL','A1')");
        assertThat(eligibility.check(p)).isEqualTo(Result.REVIEW_NO_LONGER_PENDING);
    }

    @Test void historicalReviewUsesImmutableDecisionSnapshotNotEditedRevision() {
        user(7, "TEACHER"); course();
        var p = notice(MailTemplate.COURSE_REVIEW_RESULT);
        jdbc.update("insert into course_review_decisions(id,revision_id,course_id,submission_id,reviewer_id,teacher_id,decision,course_name,recipient_email,rejection_reason,reviewed_at) values(?,12,11,?,8,7,'REJECTED','Snapshot course','student@example.test','Historical reason',?)",
                p.decisionId(), p.submissionId(), Timestamp.from(NOW));
        jdbc.update("update course_revisions set status='DRAFT',rejection_reason='New mutable reason' where id=12");
        assertThat(eligibility.check(p)).isEqualTo(Result.ALLOW);
        jdbc.update("delete from courses where id=11");
        assertThat(eligibility.check(p)).isEqualTo(Result.ALLOW);
        jdbc.update("update course_review_decisions set rejection_reason='Different historical reason'");
        assertThat(eligibility.check(p)).isEqualTo(Result.GENERATION_STALE);
        jdbc.update("update course_review_decisions set rejection_reason='Historical reason',teacher_id=8");
        assertThat(eligibility.check(p)).isEqualTo(Result.GENERATION_STALE);
    }

    @Test void consumedRegistrationAndMissingResetCannotBecomeEligible() {
        registration();
        jdbc.update("delete from registration_otps");
        assertThat(eligibility.check(auth(MailTemplate.REGISTRATION_OTP))).isEqualTo(Result.GENERATION_STALE);
        user(7, "STUDENT");
        assertThat(eligibility.check(auth(MailTemplate.PASSWORD_RESET))).isEqualTo(Result.GENERATION_STALE);
        reset(42);
        jdbc.update("update users set email='changed@example.test' where id=7");
        assertThat(eligibility.check(auth(MailTemplate.PASSWORD_RESET))).isEqualTo(Result.RECIPIENT_EMAIL_CHANGED);
    }

    @Test void invitationInactiveWrongRoleAndMetadataExpirySkip() {
        user(7, "TEACHER");
        jdbc.update("update users set invitation_generation=?,must_change_password=true,is_active=false where id=7", UUID.fromString(GEN));
        var p = auth(MailTemplate.TEACHER_INVITATION);
        assertThat(eligibility.check(p)).isEqualTo(Result.RECIPIENT_UNAVAILABLE);
        jdbc.update("update users set is_active=true,role='STUDENT' where id=7");
        assertThat(eligibility.check(p)).isEqualTo(Result.RECIPIENT_UNAVAILABLE);
        jdbc.update("update users set role='TEACHER' where id=7");
        assertThat(eligibility.check(MailEligibilityTests.auth(MailTemplate.TEACHER_INVITATION, 29))).isEqualTo(Result.CODE_EXPIRED);
        assertThat(eligibility.check(MailEligibilityTests.auth(MailTemplate.TEACHER_INVITATION, 30))).isEqualTo(Result.ALLOW);
    }

    @Test void nativeConnectionFailureIsSanitizedRetryable() throws Exception {
        var connection = dataSource.getConnection();
        connection.close();
        var unavailable = new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
        var failing = new MailEligibilityService(unavailable, manager, encoder, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> failing.check(auth(MailTemplate.PASSWORD_RESET)))
                .isInstanceOf(com.learne.backend.jobs.service.RetryableJobException.class)
                .hasMessage("MAIL_ELIGIBILITY_UNAVAILABLE").hasNoCause();
    }
}
