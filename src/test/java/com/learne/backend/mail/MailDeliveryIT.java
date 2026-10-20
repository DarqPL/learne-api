package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;

@Import(MailDeliveryIT.TransportFixture.class)
@TestPropertySource(properties = {
        "app.job-crypto.active-key-id=mail-test",
        "app.job-crypto.keys-json={\"mail-test\":\"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=\"}",
        "app.mail.frontend-base-url=https://learne.test"})
class MailDeliveryIT extends MailPostgresTestSupport {
    @TestConfiguration(proxyBeanMethods = false)
    static class TransportFixture {
        @Bean FakeTransport fakeMailTransport() { return new FakeTransport(); }
    }
    static class FakeTransport implements MailTransport {
        final List<SimpleMailMessage> delivered = new ArrayList<>();
        RuntimeException nextFailure;
        Runnable onSend;
        public void send(SimpleMailMessage message) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (onSend != null) onSend.run();
            if (nextFailure != null) { var failure = nextFailure; nextFailure = null; throw failure; }
            delivered.add(new SimpleMailMessage(message));
        }
    }
    @Autowired MailJobProducer producer;
    @Autowired JobRegistry registry;
    @Autowired FakeTransport transport;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    @Autowired com.learne.backend.jobs.crypto.JobPayloadCipher cipher;
    @Autowired MailPayloadCodec codec;
    Instant now;
    String generation;
    @BeforeEach void resetTransport() {
        transport.delivered.clear(); transport.nextFailure = null; transport.onSend = null;
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS); generation = UUID.randomUUID().toString();
    }
    void user(long id, String role) {
        jdbc.update("insert into users(id,email,password_hash,full_name,role) values(?,?,?,?,?)", id,
                id == 7 ? "student@example.test" : "u" + id + "@example.test", passwords.encode("temporary-password"), "Recipient", role);
    }
    void course() {
        user(8, "TEACHER");
        jdbc.update("insert into courses(id,teacher_id,name,type,level,status) values(11,8,'Current course','GENERAL','A1','DRAFT')");
        jdbc.update("insert into course_revisions(id,course_id,teacher_id,revision_number,status,name,type,level,rejection_reason) values(12,11,8,1,'PENDING_REVIEW','Current course','GENERAL','A1','New mutable reason')");
    }
    MailPayload seed(MailTemplate template) {
        switch (template) {
            case REGISTRATION_OTP -> jdbc.update("insert into registration_otps(email,full_name,password_hash,otp_hash,expires_at,mail_generation) values('student@example.test','Student','hash','otp-hash',?,?)", Timestamp.from(now.plusSeconds(3600)), UUID.fromString(generation));
            case PASSWORD_RESET -> {
                user(7, "STUDENT");
                jdbc.update("insert into password_reset_tokens(id,user_id,token_hash,expires_at) values(42,7,'token-hash',?)", Timestamp.from(now.plusSeconds(3600)));
            }
            case TEACHER_INVITATION -> {
                user(7, "TEACHER");
                jdbc.update("update users set invitation_generation=?,must_change_password=true where id=7", UUID.fromString(generation));
            }
            case PAYMENT_CONFIRMATION -> {
                user(7, "STUDENT");
                jdbc.update("insert into orders(id,user_id,order_type,status,original_amount_vnd,final_amount_vnd,payment_provider,expires_at) values(13,7,'COURSE_PURCHASE','PAID',100,100,'INTERNAL',?)", Timestamp.from(now));
            }
            case COURSE_PENDING_REVIEW -> { user(7, "ADMIN"); course(); }
            case COURSE_REVIEW_RESULT -> { user(7, "TEACHER"); course(); }
        }
        boolean auth = MailPayloadCodec.requiresSecret(template);
        boolean course = template == MailTemplate.COURSE_PENDING_REVIEW || template == MailTemplate.COURSE_REVIEW_RESULT;
        Map<String, String> values = switch (template) {
            case REGISTRATION_OTP, PASSWORD_RESET, TEACHER_INVITATION -> Map.of();
            case PAYMENT_CONFIRMATION -> Map.of("amountVnd", "100", "itemDescription", "Item", "paidAt", now.toString());
            case COURSE_PENDING_REVIEW -> Map.of("courseName", "Snapshot course", "teacherName", "Teacher", "submittedAt", now.toString());
            case COURSE_REVIEW_RESULT -> Map.of("courseName", "Snapshot course", "decision", "REJECTED", "rejectionReason", "Historical rejection reason", "reviewedAt", now.toString());
        };
        var p = new MailPayload(template == MailTemplate.REGISTRATION_OTP ? 0 : 7, "student@example.test", "Recipient", template,
                auth ? template == MailTemplate.PASSWORD_RESET ? "42" : generation : null,
                auth ? now.plusSeconds(template == MailTemplate.TEACHER_INVITATION ? 86400 : 3600) : null,
                course ? 11L : null, course ? 12L : null, course ? UUID.fromString(generation) : null,
                template == MailTemplate.COURSE_REVIEW_RESULT ? UUID.randomUUID() : null, template == MailTemplate.PAYMENT_CONFIRMATION ? 13L : null, values);
        if (template == MailTemplate.COURSE_REVIEW_RESULT) jdbc.update("insert into course_review_decisions(id,revision_id,course_id,submission_id,reviewer_id,teacher_id,decision,course_name,recipient_email,rejection_reason,reviewed_at) values(?,12,11,?,8,7,'REJECTED','Snapshot course','student@example.test','Historical rejection reason',?)", p.decisionId(), p.submissionId(), Timestamp.from(now));
        return p;
    }
    UUID enqueue(MailPayload p) {
        byte[] secret = MailPayloadCodec.requiresSecret(p.template()) ? "temporary-password".getBytes(StandardCharsets.UTF_8) : null;
        return new TransactionTemplate(manager).execute(status -> producer.enqueue(p.template().jobType(), UUID.randomUUID().toString(), p, secret));
    }
    JobLease claim(MailTemplate template) {
        return store.claim(JobRegistry.queueFor(template.jobType()), Map.of(template.jobType(), Set.of(1)), Duration.ofSeconds(30)).orElseThrow();
    }
    JobHandler handler(JobLease lease) { return registry.find(lease.type(), lease.payloadVersion()).orElseThrow(); }
    void execute(JobLease lease) { handler(lease).execute(lease); }
    void terminal(UUID id, String state, String code) {
        var row = jdbc.queryForMap("select state,last_error_code,secret_key_id,secret_nonce,secret_ciphertext from background_jobs where id=?", id);
        assertThat(row).containsEntry("state", state).containsEntry("last_error_code", code);
        assertThat(row.get("secret_key_id")).isNull(); assertThat(row.get("secret_nonce")).isNull(); assertThat(row.get("secret_ciphertext")).isNull();
    }
    @ParameterizedTest @EnumSource(MailTemplate.class)
    void allSixTemplatesDeliverThroughRealProducerClaimsAndRegisteredHandlers(MailTemplate template) {
        var p = seed(template); var id = enqueue(p); execute(claim(template));
        terminal(id, "SUCCEEDED", null);
        assertThat(transport.delivered).hasSize(1);
        if (template == MailTemplate.COURSE_REVIEW_RESULT) assertThat(transport.delivered.getFirst().getText()).contains("Historical rejection reason").doesNotContain("New mutable reason");
    }
    @ParameterizedTest @EnumSource(value = MailTemplate.class, names = {"REGISTRATION_OTP", "PASSWORD_RESET"})
    void exhaustedAuthSkipsBeforeAttemptingInvalidCiphertext(MailTemplate template) {
        var p = seed(template); var id = enqueue(p);
        String table = template == MailTemplate.REGISTRATION_OTP ? "registration_otps" : "password_reset_tokens";
        jdbc.update("update " + table + " set attempts=5");
        jdbc.update("update background_jobs set secret_key_id='unavailable-key' where id=?", id);
        execute(claim(template)); terminal(id, "SKIPPED", "VERIFICATION_ATTEMPTS_EXHAUSTED");
        assertThat(transport.delivered).isEmpty();
    }
    @ParameterizedTest @EnumSource(value = MailTemplate.class, names = {"REGISTRATION_OTP", "PASSWORD_RESET", "TEACHER_INVITATION"})
    void replacedGenerationSkipsBeforeDecryptingInvalidCiphertext(MailTemplate template) {
        var p = seed(template); var id = enqueue(p);
        switch (template) {
            case REGISTRATION_OTP -> jdbc.update("update registration_otps set mail_generation=?", UUID.randomUUID());
            case PASSWORD_RESET -> jdbc.update("update password_reset_tokens set used_at=clock_timestamp()");
            case TEACHER_INVITATION -> jdbc.update("update users set invitation_generation=? where id=7", UUID.randomUUID());
            default -> throw new AssertionError();
        }
        jdbc.update("update background_jobs set secret_key_id='unavailable-key' where id=?", id);
        execute(claim(template)); terminal(id, "SKIPPED", "GENERATION_STALE"); assertThat(transport.delivered).isEmpty();
    }
    @Test void expiredMetadataSkipsWithoutDecrypt() {
        var p = seed(MailTemplate.REGISTRATION_OTP); var id = enqueue(p);
        var json = mapper.readTree(mapper.writeValueAsString(p)).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) json).put("expiresAt", now.minusSeconds(1).toString());
        jdbc.update("update background_jobs set payload=cast(? as jsonb),secret_key_id='unavailable-key' where id=?", mapper.writeValueAsString(json), id);
        execute(claim(p.template())); terminal(id, "SKIPPED", "CODE_EXPIRED"); assertThat(transport.delivered).isEmpty();
    }
    static class DeliveryClock extends Clock {
        volatile Instant current;
        DeliveryClock(Instant current) { this.current = current; }
        @Override public Instant instant() { return current; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
    }
    MailJobHandler controlledHandler(MailTemplate template, Clock clock, MailTemplateRenderer renderer) {
        var eligibility = new MailEligibilityService(jdbc, manager, passwords, clock);
        return new MailJobHandler(template.jobType(), store, cipher, codec, eligibility, renderer, transport);
    }
    @ParameterizedTest @EnumSource(value = MailTemplate.class, names = {"REGISTRATION_OTP", "PASSWORD_RESET", "TEACHER_INVITATION"})
    void expiryAfterRealClaimUsesTemplateTerminalPolicyAndPurgesSecret(MailTemplate template) {
        var p = seed(template); var id = enqueue(p); var lease = claim(template);
        var clock = new DeliveryClock(p.expiresAt().plusSeconds(1));
        controlledHandler(template, clock, new MailTemplateRenderer("https://learne.test", "")).execute(lease);
        terminal(id, template == MailTemplate.TEACHER_INVITATION ? "DEAD" : "SKIPPED", "CODE_EXPIRED");
        assertThat(transport.delivered).isEmpty();
    }
    @ParameterizedTest @EnumSource(value = MailTemplate.class, names = {"REGISTRATION_OTP", "PASSWORD_RESET", "TEACHER_INVITATION"})
    void expiryWhileRenderingPausedUsesPresendTerminalPolicyAndPurgesSecret(MailTemplate template) throws Exception {
        var p = seed(template); var id = enqueue(p); var lease = claim(template);
        var clock = new DeliveryClock(now);
        var renderer = org.mockito.Mockito.spy(new MailTemplateRenderer("https://learne.test", ""));
        var rendered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            var message = invocation.callRealMethod();
            rendered.countDown();
            if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("Rendering pause timed out");
            return message;
        }).when(renderer).render(org.mockito.ArgumentMatchers.any(MailPayload.class), org.mockito.ArgumentMatchers.any(byte[].class));
        var controlled = controlledHandler(template, clock, renderer);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> controlled.execute(lease));
            assertThat(rendered.await(5, TimeUnit.SECONDS)).isTrue();
            clock.current = p.expiresAt().plusSeconds(1);
            resume.countDown();
            result.get(5, TimeUnit.SECONDS);
            terminal(id, template == MailTemplate.TEACHER_INVITATION ? "DEAD" : "SKIPPED", "CODE_EXPIRED");
            assertThat(transport.delivered).isEmpty();
        } finally {
            resume.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void legacyReviewWithoutSubmissionSurvivesCourseDeletionAndRealReclaimDelivery() {
        var current = seed(MailTemplate.COURSE_REVIEW_RESULT);
        jdbc.update("update course_review_decisions set submission_id=null where id=?", current.decisionId());
        jdbc.update("delete from courses where id=?", current.courseId());
        var legacy = new MailPayload(current.recipientUserId(), current.recipientEmail(), current.recipientName(),
                current.template(), null, null, current.courseId(), current.revisionId(), null,
                current.decisionId(), null, current.values());
        var id = enqueue(legacy); var firstLease = claim(legacy.template());
        assertThat(codec.decode(firstLease.type(), firstLease.payloadJson())).isEqualTo(legacy);
        jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second' where id=?", id);
        assertThat(store.recoverExpired(10)).isEqualTo(1);
        var reclaimed = claim(legacy.template());
        assertThat(reclaimed.token()).isNotEqualTo(firstLease.token());
        execute(reclaimed);
        terminal(id, "SUCCEEDED", null);
        assertThat(transport.delivered).hasSize(1);
        assertThat(transport.delivered.getFirst().getText()).contains("Historical rejection reason", "/teacher/content/courses/11");
    }
    @Test void changedEmailSkipsWithoutRerouting() {
        var p = seed(MailTemplate.PAYMENT_CONFIRMATION); var id = enqueue(p);
        jdbc.update("update users set email='changed@example.test' where id=7"); execute(claim(p.template()));
        terminal(id, "SKIPPED", "RECIPIENT_EMAIL_CHANGED"); assertThat(transport.delivered).isEmpty();
    }
    @Test void lockedActiveAdminDeliversButInactiveAdminSkips() {
        var p = seed(MailTemplate.COURSE_PENDING_REVIEW);
        jdbc.update("update users set is_locked=true where id=7"); var first = enqueue(p); execute(claim(p.template())); terminal(first, "SUCCEEDED", null);
        jdbc.update("update users set is_active=false where id=7"); var second = enqueue(p); execute(claim(p.template())); terminal(second, "SKIPPED", "RECIPIENT_UNAVAILABLE");
        assertThat(transport.delivered).hasSize(1);
    }
    @Test void supersededPendingRevisionSkips() {
        var p = seed(MailTemplate.COURSE_PENDING_REVIEW); var id = enqueue(p);
        jdbc.update("insert into course_revisions(id,course_id,teacher_id,revision_number,status,name,type,level) values(14,11,8,2,'DRAFT','New','GENERAL','A1')");
        execute(claim(p.template())); terminal(id, "SKIPPED", "REVIEW_NO_LONGER_PENDING"); assertThat(transport.delivered).isEmpty();
    }
    @Test void invitationWrongCurrentPasswordSkips() {
        var p = seed(MailTemplate.TEACHER_INVITATION); var id = enqueue(p);
        jdbc.update("update users set password_hash=? where id=7", passwords.encode("rotated-password"));
        execute(claim(p.template())); terminal(id, "SKIPPED", "GENERATION_STALE"); assertThat(transport.delivered).isEmpty();
    }
    @Test void typedTemporaryFailureRetriesRealQueueAndDoesNotResettlePaidOrder() {
        var p = seed(MailTemplate.PAYMENT_CONFIRMATION); var id = enqueue(p); var lease = claim(p.template());
        transport.nextFailure = new RetryableJobException("THROTTLED", null);
        assertThatThrownBy(() -> execute(lease)).isInstanceOf(RetryableJobException.class).hasMessage("THROTTLED");
        assertThat(store.retry(lease, Instant.now().minusSeconds(1), "THROTTLED")).isTrue();
        var retry = claim(p.template()); assertThat(retry.attempt()).isEqualTo(2); execute(retry);
        terminal(id, "SUCCEEDED", null); assertThat(transport.delivered).hasSize(1);
        assertThat(jdbc.queryForObject("select status from orders where id=13", String.class)).isEqualTo("PAID");
    }
    @Test void retryRechecksCurrentRecipientEligibility() {
        var p = seed(MailTemplate.PAYMENT_CONFIRMATION); var id = enqueue(p); var lease = claim(p.template());
        transport.nextFailure = new RetryableJobException("THROTTLED", null);
        assertThatThrownBy(() -> execute(lease)).isInstanceOf(RetryableJobException.class);
        assertThat(store.retry(lease, Instant.now().minusSeconds(1), "THROTTLED")).isTrue();
        jdbc.update("update users set email='changed@example.test' where id=7");
        execute(claim(p.template())); terminal(id, "SKIPPED", "RECIPIENT_EMAIL_CHANGED"); assertThat(transport.delivered).isEmpty();
    }
    @Test void independentAdminFailureDoesNotResendSuccessfulAdminJob() {
        var p = seed(MailTemplate.COURSE_PENDING_REVIEW); user(9, "ADMIN");
        var other = new MailPayload(9, "u9@example.test", "Other admin", p.template(), null, null,
                p.courseId(), p.revisionId(), p.submissionId(), null, null, p.values());
        var firstId = enqueue(p); var failed = claim(p.template());
        transport.nextFailure = new RetryableJobException("THROTTLED", null);
        assertThatThrownBy(() -> execute(failed)).isInstanceOf(RetryableJobException.class);
        assertThat(store.retry(failed, Instant.now().plusSeconds(60), "THROTTLED")).isTrue();
        var secondId = enqueue(other); execute(claim(p.template())); terminal(secondId, "SUCCEEDED", null);
        jdbc.update("update background_jobs set available_at=clock_timestamp()-interval '1 second' where id=?", firstId);
        execute(claim(p.template())); terminal(firstId, "SUCCEEDED", null);
        assertThat(transport.delivered).hasSize(2);
        assertThat(transport.delivered.stream().map(message -> message.getTo()[0])).containsExactly("u9@example.test", "student@example.test");
        assertThat(store.claim(QueueName.MAIL_NOTICE, registry.supported(QueueName.MAIL_NOTICE), Duration.ofSeconds(30))).isEmpty();
    }
    @Test void eligibleAuthWithMissingSecretIsPermanentAndPurged() {
        var p = seed(MailTemplate.REGISTRATION_OTP); var id = enqueue(p);
        jdbc.update("update background_jobs set secret_key_id=null,secret_nonce=null,secret_ciphertext=null where id=?", id);
        var lease = claim(p.template());
        assertThatThrownBy(() -> execute(lease)).isInstanceOf(PermanentJobException.class).hasMessage("INVALID_INPUT").hasNoCause();
        handler(lease).failPermanently(lease, "INVALID_INPUT"); terminal(id, "DEAD", "INVALID_INPUT"); assertThat(transport.delivered).isEmpty();
    }
    @Test void permanentTransportFailurePropagatesThenFencedDeadPurgesSecret() {
        var p = seed(MailTemplate.REGISTRATION_OTP); var id = enqueue(p); var lease = claim(p.template());
        transport.nextFailure = new PermanentJobException("INVALID_INPUT");
        assertThatThrownBy(() -> execute(lease)).isInstanceOf(PermanentJobException.class).hasMessage("INVALID_INPUT");
        handler(lease).failPermanently(lease, "INVALID_INPUT"); terminal(id, "DEAD", "INVALID_INPUT"); assertThat(transport.delivered).isEmpty();
    }
    @Test void knownButMismatchedTemplateAndInvalidValuesNeverReachTransport() {
        var p = seed(MailTemplate.REGISTRATION_OTP);
        for (String changed : List.of(
                mapper.writeValueAsString(p).replace("REGISTRATION_OTP", "PASSWORD_RESET"),
                mapper.writeValueAsString(p).replace("\"values\":{}", "\"values\":{\"code\":\"private-marker\"}"))) {
            var id = enqueue(p); jdbc.update("update background_jobs set payload=cast(? as jsonb) where id=?", changed, id);
            var lease = claim(p.template());
            assertThatThrownBy(() -> execute(lease)).isInstanceOf(PermanentJobException.class).hasMessage("INVALID_INPUT").hasNoCause();
            handler(lease).failPermanently(lease, "INVALID_INPUT"); terminal(id, "DEAD", "INVALID_INPUT");
        }
        assertThat(transport.delivered).isEmpty();
    }
    @Test void staleLeaseCannotSendOrOverwriteNewOwner() {
        var p = seed(MailTemplate.PAYMENT_CONFIRMATION); var id = enqueue(p); var old = claim(p.template());
        jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second' where id=?", id);
        store.recoverExpired(10); var current = claim(p.template()); execute(old);
        assertThat(transport.delivered).isEmpty(); assertThat(store.isLeaseCurrent(current)).isTrue();
        handler(old).failPermanently(old, "INVALID_INPUT"); assertThat(store.isLeaseCurrent(current)).isTrue();
        execute(current); terminal(id, "SUCCEEDED", null);
    }
    @Test void leaseLostDuringTransportCannotOverwriteRetryOwner() {
        var p = seed(MailTemplate.PAYMENT_CONFIRMATION); var id = enqueue(p); var lease = claim(p.template());
        transport.onSend = () -> { assertThat(store.retry(lease, Instant.now(), "THROTTLED")).isTrue(); };
        execute(lease); assertThat(jdbc.queryForObject("select state from background_jobs where id=?", String.class, id)).isEqualTo("RETRY_WAIT");
    }
    @Test void interruptedWorkerStartsNoTransport() {
        var p = seed(MailTemplate.PAYMENT_CONFIRMATION); enqueue(p); var lease = claim(p.template());
        Thread.currentThread().interrupt();
        try { execute(lease); assertThat(transport.delivered).isEmpty(); }
        finally { Thread.interrupted(); }
    }
    @Test void malformedPayloadIsSafePermanentFailureAndPurgesCiphertext() {
        var p = seed(MailTemplate.REGISTRATION_OTP); var id = enqueue(p);
        jdbc.update("update background_jobs set payload=cast(? as jsonb) where id=?", "{\"secret\":\"private-marker\"}", id);
        var lease = claim(p.template());
        assertThatThrownBy(() -> execute(lease)).isInstanceOf(PermanentJobException.class).hasMessage("INVALID_INPUT").hasNoCause();
        handler(lease).failPermanently(lease, "INVALID_INPUT"); terminal(id, "DEAD", "INVALID_INPUT"); assertThat(transport.delivered).isEmpty();
    }
    @ParameterizedTest @EnumSource(value = MailTemplate.class, names = {"REGISTRATION_OTP", "PASSWORD_RESET", "TEACHER_INVITATION"})
    void runtimeExpiryTerminalPolicyPurgesAuthSecret(MailTemplate template) {
        var p = seed(template); var id = enqueue(p); var lease = claim(template);
        handler(lease).failPermanently(lease, "JOB_EXPIRED"); terminal(id, template == MailTemplate.TEACHER_INVITATION ? "DEAD" : "SKIPPED", "JOB_EXPIRED");
    }
}
