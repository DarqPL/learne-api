package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learne.backend.auth.registration.dto.*;
import com.learne.backend.auth.registration.service.StudentRegistrationService;
import com.learne.backend.auth.session.dto.PasswordResetRequest;
import com.learne.backend.auth.session.service.PasswordResetService;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.*;
import com.learne.backend.ratelimit.RateLimitService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Import(MailDeliveryIT.TransportFixture.class)
@TestPropertySource(properties = {
        "app.job-crypto.active-key-id=mail-test",
        "app.job-crypto.keys-json={\"mail-test\":\"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=\"}",
        "app.mail.frontend-base-url=https://learne.test"})
class AuthMailTransactionsIT extends MailPostgresTestSupport {
    @Autowired StudentRegistrationService registration;
    @Autowired PasswordResetService reset;
    @Autowired JobRegistry registry;
    @Autowired MailPayloadCodec codec;
    @Autowired com.learne.backend.jobs.crypto.JobPayloadCipher cipher;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @Autowired MailDeliveryIT.FakeTransport transport;
    @MockitoSpyBean JobStore jobStore;
    @MockitoSpyBean RateLimitService limiter;
    @Autowired com.learne.backend.domain.auth.RegistrationOtpRepository pendingOtps;
    @BeforeEach void clearTransport() {
        transport.delivered.clear();
        transport.nextFailure = null;
        transport.onSend = null;
    }
    JobStore targetStore() { return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobStore); }
    RequestRegistrationOtpRequest request() {
        return new RequestRegistrationOtpRequest("N".repeat(255), "student@example.test", "Password123", "Password123");
    }
    JobLease claim(MailTemplate template) {
        return store.claim(QueueName.MAIL_AUTH, Map.of(template.jobType(), Set.of(1)), Duration.ofSeconds(30)).orElseThrow();
    }
    void execute(JobLease lease) { registry.find(lease.type(), 1).orElseThrow().execute(lease); }
    @ParameterizedTest
    @EnumSource(value = MailTemplate.class, names = {"REGISTRATION_OTP", "PASSWORD_RESET"})
    void requestCommitsBeforeSeparateWorkerBlocksInTransport(MailTemplate template) throws Exception {
        if (template == MailTemplate.PASSWORD_RESET) user("active@example.test", true, false);
        var enteredTransport = new CountDownLatch(1);
        var releaseTransport = new CountDownLatch(1);
        transport.onSend = () -> {
            enteredTransport.countDown();
            try {
                if (!releaseTransport.await(10, TimeUnit.SECONDS)) throw new AssertionError("Transport release timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Transport interrupted", interrupted);
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var accepted = executor.submit(() -> {
                if (template == MailTemplate.REGISTRATION_OTP) registration.requestOtp(request());
                else reset.requestReset(new PasswordResetRequest("active@example.test"));
            });
            accepted.get(5, TimeUnit.SECONDS);
            assertThat(enteredTransport.getCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from background_jobs where state='READY'", Long.class)).isEqualTo(1);
            String table = template == MailTemplate.REGISTRATION_OTP ? "registration_otps" : "password_reset_tokens";
            var committed = jdbc.queryForMap("select * from " + table);
            var lease = claim(template);
            var worker = executor.submit(() -> execute(lease));
            assertThat(enteredTransport.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(accepted.isDone()).isTrue();
            assertThat(worker.isDone()).isFalse();
            assertThat(jdbc.queryForMap("select * from " + table)).isEqualTo(committed);
            assertThat(jdbc.queryForObject("select state from background_jobs where id=?", String.class, lease.jobId())).isEqualTo("RUNNING");
            releaseTransport.countDown();
            worker.get(5, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select state from background_jobs where id=?", String.class, lease.jobId())).isEqualTo("SUCCEEDED");
        } finally {
            releaseTransport.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            transport.onSend = null;
        }
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedReplacementOrResendRestoresOriginalPendingAndJobWithoutRefund(boolean resend) {
        registration.requestOtp(request());
        jdbc.update("update registration_otps set attempts=3" + (resend ? ",expires_at=clock_timestamp()-interval '1 second'" : ""));
        var original = jdbc.queryForMap("select * from registration_otps");
        var originalJob = jdbc.queryForObject("select to_jsonb(j)::text from background_jobs j", String.class);
        var reservations = new AtomicInteger();
        clearInvocations(limiter);
        doAnswer(call -> { reservations.incrementAndGet(); return null; }).when(limiter)
                .requireAllowed(eq("register-send-email"), any(), eq("student@example.test"));
        doAnswer(call -> {
            pendingOtps.flush();
            var mutated = jdbc.queryForMap("select * from registration_otps");
            assertThat(mutated.get("mail_generation")).isNotEqualTo(original.get("mail_generation"));
            assertThat(mutated.get("otp_hash")).isNotEqualTo(original.get("otp_hash"));
            assertThat(mutated.get("attempts")).isEqualTo(0);
            throw new JobEnqueueException(null);
        }).when(targetStore()).enqueue(any());
        assertThatThrownBy(() -> {
            if (resend) registration.resendOtp(new ResendRegistrationOtpRequest("student@example.test"));
            else registration.requestOtp(request());
        }).isInstanceOf(JobEnqueueException.class);
        assertThat(jdbc.queryForMap("select * from registration_otps")).isEqualTo(original);
        assertThat(jdbc.queryForObject("select to_jsonb(j)::text from background_jobs j", String.class)).isEqualTo(originalJob);
        assertThat(reservations.get()).isEqualTo(1);
        verify(limiter).requireAllowed(eq("register-send-email"), any(), eq("student@example.test"));
        verifyNoMoreInteractions(limiter);
    }
    @Test void registrationAcceptsBeforeTransportAndRetryKeepsGenerationAndExpiry() {
        transport.delivered.clear();
        registration.requestOtp(request());
        assertThat(transport.delivered).isEmpty();
        var pending = jdbc.queryForMap("select * from registration_otps");
        var lease = claim(MailTemplate.REGISTRATION_OTP);
        var payload = codec.decode(lease.type(), lease.payloadJson());
        byte[] secret = cipher.decrypt(lease.jobId(), lease.type(), payload.generation(), lease.secret());
        try {
            assertThat(passwords.matches(new String(secret, java.nio.charset.StandardCharsets.UTF_8), (String) pending.get("otp_hash"))).isTrue();
        } finally { Arrays.fill(secret, (byte) 0); }
        assertThat(payload.generation()).isEqualTo(pending.get("mail_generation").toString());
        assertThat(jdbc.queryForObject("select dedupe_key from background_jobs", String.class)).isEqualTo("registration-otp:" + payload.generation());
        transport.nextFailure = new RetryableJobException("THROTTLED", null);
        assertThatThrownBy(() -> execute(lease)).isInstanceOf(RetryableJobException.class);
        assertThat(store.retry(lease, Instant.now().minusSeconds(1), "THROTTLED")).isTrue();
        execute(claim(MailTemplate.REGISTRATION_OTP));
        assertThat(transport.delivered).hasSize(1);
        assertThat(jdbc.queryForMap("select * from registration_otps")).isEqualTo(pending);
        assertThatThrownBy(() -> registration.resendOtp(new ResendRegistrationOtpRequest("student@example.test"))).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        jdbc.update("update registration_otps set expires_at=clock_timestamp()-interval '1 second'");
        registration.resendOtp(new ResendRegistrationOtpRequest("student@example.test"));
        assertThat(jdbc.queryForObject("select mail_generation from registration_otps", UUID.class).toString()).isNotEqualTo(payload.generation());
    }
    @Test void queueFailureRollsBackPendingAfterSaveAndDoesNotRefundQuota() {
        doAnswer(call -> {
            assertThat(jdbc.queryForObject("select count(*) from registration_otps", Long.class)).isEqualTo(1);
            throw new IllegalStateException("Injected queue failure");
        }).when(targetStore()).enqueue(any());
        assertThatThrownBy(() -> registration.requestOtp(request())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from registration_otps", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isZero();
        verify(limiter).requireAllowed(eq("register-send-email"), any(), eq("student@example.test"));
    }
    void user(String email, boolean active, boolean locked) {
        jdbc.update("insert into users(email,password_hash,full_name,role,is_active,is_locked) values(?,'hash','Student','STUDENT',?,?)", email, active, locked);
    }
    @Test void replacedRegistrationAndExhaustedCurrentGenerationSkipThroughWorker() {
        transport.delivered.clear();
        registration.requestOtp(request());
        var old = claim(MailTemplate.REGISTRATION_OTP);
        registration.requestOtp(request());
        execute(old);
        assertThat(jdbc.queryForObject("select state from background_jobs where id=?", String.class, old.jobId())).isEqualTo("SKIPPED");
        var current = claim(MailTemplate.REGISTRATION_OTP);
        jdbc.update("update registration_otps set attempts=5");
        execute(current);
        assertThat(jdbc.queryForObject("select state from background_jobs where id=?", String.class, current.jobId())).isEqualTo("SKIPPED");
        assertThat(transport.delivered).isEmpty();
    }
    @Test void usedResetGenerationSkipsThroughWorker() {
        transport.delivered.clear();
        user("active@example.test", true, false);
        reset.requestReset(new PasswordResetRequest("active@example.test"));
        var lease = claim(MailTemplate.PASSWORD_RESET);
        jdbc.update("update password_reset_tokens set used_at=clock_timestamp()");
        execute(lease);
        assertThat(jdbc.queryForObject("select state from background_jobs where id=?", String.class, lease.jobId())).isEqualTo("SKIPPED");
        assertThat(transport.delivered).isEmpty();
    }
    @Test void resetOnlyQueuesForActiveUnlockedAccountsAndRollsBackTokenOnQueueFailure() {
        user("active@example.test", true, false); user("inactive@example.test", false, false); user("locked@example.test", true, true);
        for (String email : List.of("unknown@example.test", "inactive@example.test", "locked@example.test")) reset.requestReset(new PasswordResetRequest(email));
        assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isZero();
        doAnswer(call -> {
            assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens", Long.class)).isEqualTo(1);
            throw new IllegalStateException("Injected queue failure");
        }).when(targetStore()).enqueue(any());
        assertThatThrownBy(() -> reset.requestReset(new PasswordResetRequest("active@example.test"))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens", Long.class)).isZero();
        doCallRealMethod().when(targetStore()).enqueue(any());
        reset.requestReset(new PasswordResetRequest("active@example.test"));
        var lease = claim(MailTemplate.PASSWORD_RESET);
        var payload = codec.decode(lease.type(), lease.payloadJson());
        assertThat(Long.parseLong(payload.generation())).isPositive();
        assertThat(jdbc.queryForObject("select id from password_reset_tokens", Long.class).toString()).isEqualTo(payload.generation());
        byte[] secret = cipher.decrypt(lease.jobId(), lease.type(), payload.generation(), lease.secret());
        try {
            assertThat(passwords.matches(new String(secret, java.nio.charset.StandardCharsets.UTF_8), jdbc.queryForObject("select token_hash from password_reset_tokens", String.class))).isTrue();
        } finally { Arrays.fill(secret, (byte) 0); }
        execute(lease);
    }
}
