package com.learne.backend.auth.registration.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.learne.backend.auth.registration.dto.*;
import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.domain.auth.*;
import com.learne.backend.ratelimit.*;
import com.learne.backend.shared.api.ApiException;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class StudentRegistrationServiceTests {
    private static final String EMAIL = "i@example.com";
    private static final String POLICY = "register-send-email";
    private static final RateLimitDimension DIMENSION = RateLimitDimension.EMAIL;
    private static final String PASSWORD = "Password123";
    @Mock private RegistrationOtpRepository pendingOtps;
    @Mock private UserRepository users;
    @Mock private PasswordEncoder passwords;
    @Mock private PasswordPolicy passwordPolicy;
    @Mock private OtpGenerator generator;
    @Mock private RegistrationMailService mail;
    @Mock private CacheInvalidationService cache;
    @Mock private RateLimitService limiter;
    @InjectMocks private StudentRegistrationService service;

    private RequestRegistrationOtpRequest request() {
        return new RequestRegistrationOtpRequest(" Student Name ", " I@Example.COM ", PASSWORD, PASSWORD);
    }

    private ResendRegistrationOtpRequest resend() {
        return new ResendRegistrationOtpRequest(" I@Example.COM ");
    }

    private RegistrationOtp pending(boolean expired) {
        RegistrationOtp pending = new RegistrationOtp();
        ReflectionTestUtils.setField(pending, "id", 42L);
        pending.setEmail(EMAIL);
        pending.setFullName("Student Name");
        pending.setPasswordHash("password-hash");
        pending.setOtpHash("old-otp-hash");
        pending.setExpiresAt(OffsetDateTime.now().plusMinutes(expired ? -1 : 5));
        pending.setAttempts(4);
        return spy(pending);
    }

    private void generatedOtp() {
        when(generator.generate()).thenReturn("123456");
        when(passwords.encode("123456")).thenReturn("new-otp-hash");
    }

    private void assertStatus(Runnable action, HttpStatus status, String message) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(status);
            assertThat(error.getMessage()).isEqualTo(message);
        });
    }

    private void assertUnchanged(RegistrationOtp pending, OffsetDateTime expiry) {
        verify(pending, never()).setOtpHash(any());
        verify(pending, never()).setExpiresAt(any());
        verify(pending, never()).setAttempts(anyInt());
        assertThat(pending.getId()).isEqualTo(42L);
        assertThat(pending.getEmail()).isEqualTo(EMAIL);
        assertThat(pending.getFullName()).isEqualTo("Student Name");
        assertThat(pending.getPasswordHash()).isEqualTo("password-hash");
        assertThat(pending.getOtpHash()).isEqualTo("old-otp-hash");
        assertThat(pending.getExpiresAt()).isEqualTo(expiry);
        assertThat(pending.getAttempts()).isEqualTo(4);
    }

    @Test
    void invalidPasswordStopsBeforeUserLookupBudgetAndPendingWork() {
        var failure = new ApiException(HttpStatus.BAD_REQUEST, "invalid password");
        doThrow(failure).when(passwordPolicy).validate(PASSWORD, PASSWORD);
        assertThatThrownBy(() -> service.requestOtp(request())).isSameAs(failure);
        verifyNoInteractions(users, limiter, pendingOtps, generator, passwords, mail, cache);
    }

    @Test
    void existingUserStopsAfterPasswordValidationBeforeBudgetAndPendingWork() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(new User()));
        assertStatus(() -> service.requestOtp(request()), HttpStatus.CONFLICT, "Email is already registered.");
        var order = inOrder(passwordPolicy, users);
        order.verify(passwordPolicy).validate(PASSWORD, PASSWORD);
        order.verify(users).findByEmail(EMAIL);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(limiter, pendingOtps, generator, passwords, mail, cache);
    }

    private void requestGuardFailure(RateLimitException failure) {
        doThrow(failure).when(limiter).requireAllowed(POLICY, DIMENSION, EMAIL);
        assertThatThrownBy(() -> service.requestOtp(request())).isSameAs(failure);
        var order = inOrder(passwordPolicy, users, limiter);
        order.verify(passwordPolicy).validate(PASSWORD, PASSWORD);
        order.verify(users).findByEmail(EMAIL);
        order.verify(limiter).requireAllowed(POLICY, DIMENSION, EMAIL);
        verifyNoMoreInteractions(users, limiter);
        verifyNoInteractions(pendingOtps, generator, passwords, mail, cache);
    }

    @Test
    void requestDenialPrecedesPendingLookupDeletionGenerationHashingAndMail() {
        requestGuardFailure(RateLimitException.rejected(1000));
    }

    @Test
    void requestUnavailablePropagatesBeforeAnyPendingWork() {
        requestGuardFailure(RateLimitException.unavailable());
    }

    @Test
    void allowedRequestOrdersBudgetBeforeReplacementAndSendsOnceWithFiveMinuteExpiry() {
        var old = pending(false);
        when(pendingOtps.findByEmail(EMAIL)).thenReturn(Optional.of(old));
        generatedOtp();
        when(passwords.encode(PASSWORD)).thenReturn("password-hash");
        OffsetDateTime before = OffsetDateTime.now();
        service.requestOtp(request());
        OffsetDateTime after = OffsetDateTime.now();
        var order = inOrder(passwordPolicy, users, limiter, pendingOtps, generator, passwords, mail);
        order.verify(passwordPolicy).validate(PASSWORD, PASSWORD);
        order.verify(users).findByEmail(EMAIL);
        order.verify(limiter).requireAllowed(POLICY, DIMENSION, EMAIL);
        order.verify(pendingOtps).findByEmail(EMAIL);
        order.verify(pendingOtps).delete(old);
        order.verify(pendingOtps).flush();
        order.verify(generator).generate();
        order.verify(passwords).encode(PASSWORD);
        order.verify(passwords).encode("123456");
        var saved = org.mockito.ArgumentCaptor.forClass(RegistrationOtp.class);
        order.verify(pendingOtps).save(saved.capture());
        order.verify(mail).sendRegistrationOtp(EMAIL, "123456");
        assertThat(saved.getValue()).isNotSameAs(old);
        assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getValue().getFullName()).isEqualTo("Student Name");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("password-hash");
        assertThat(saved.getValue().getOtpHash()).isEqualTo("new-otp-hash");
        assertThat(saved.getValue().getAttempts()).isZero();
        assertThat(saved.getValue().getExpiresAt()).isBetween(before.plusMinutes(5), after.plusMinutes(5));
        verifyNoMoreInteractions(limiter, mail, users, pendingOtps);
        verifyNoInteractions(cache);
    }

    @Test
    void requestWithoutExistingPendingNeverDeletesOrFlushes() {
        generatedOtp();
        when(passwords.encode(PASSWORD)).thenReturn("password-hash");
        service.requestOtp(request());
        var order = inOrder(limiter, pendingOtps, generator, mail);
        order.verify(limiter).requireAllowed(POLICY, DIMENSION, EMAIL);
        order.verify(pendingOtps).findByEmail(EMAIL);
        order.verify(generator).generate();
        order.verify(pendingOtps).save(any(RegistrationOtp.class));
        order.verify(mail).sendRegistrationOtp(EMAIL, "123456");
        verifyNoMoreInteractions(pendingOtps, limiter, mail);
    }

    @Test
    void replacementFlushFailurePropagatesAfterBudgetWithoutGenerationSaveOrMail() {
        var old = pending(false);
        when(pendingOtps.findByEmail(EMAIL)).thenReturn(Optional.of(old));
        var failure = new org.springframework.dao.DataAccessResourceFailureException("fixture flush failure");
        doThrow(failure).when(pendingOtps).flush();
        assertThatThrownBy(() -> service.requestOtp(request())).isSameAs(failure);
        var order = inOrder(passwordPolicy, users, limiter, pendingOtps);
        order.verify(passwordPolicy).validate(PASSWORD, PASSWORD);
        order.verify(users).findByEmail(EMAIL);
        order.verify(limiter).requireAllowed(POLICY, DIMENSION, EMAIL);
        order.verify(pendingOtps).findByEmail(EMAIL);
        order.verify(pendingOtps).delete(old);
        order.verify(pendingOtps).flush();
        verifyNoMoreInteractions(users, limiter, pendingOtps);
        verifyNoInteractions(generator, passwords, mail, cache);
    }

    @Test
    void missingResendStopsBeforeEmailBudget() {
        assertStatus(() -> service.resendOtp(resend()), HttpStatus.BAD_REQUEST,
                "No pending registration found for this email.");
        verify(pendingOtps).findByEmail(EMAIL);
        verifyNoMoreInteractions(pendingOtps);
        verifyNoInteractions(limiter, generator, passwords, mail, users);
    }

    @Test
    void stillValidResendStopsBeforeEmailBudgetWithoutMutation() {
        var pending = pending(false);
        var expiry = pending.getExpiresAt();
        clearInvocations(pending);
        when(pendingOtps.findByEmail(EMAIL)).thenReturn(Optional.of(pending));
        assertStatus(() -> service.resendOtp(resend()), HttpStatus.BAD_REQUEST,
                "Current OTP is still valid. Please wait until it expires before requesting a new one.");
        var order = inOrder(pendingOtps, pending);
        order.verify(pendingOtps).findByEmail(EMAIL);
        order.verify(pending).getExpiresAt();
        assertUnchanged(pending, expiry);
        verifyNoMoreInteractions(pendingOtps);
        verifyNoInteractions(limiter, generator, passwords, mail, users);
    }

    private void resendGuardFailure(RateLimitException failure) {
        var pending = pending(true);
        var expiry = pending.getExpiresAt();
        clearInvocations(pending);
        when(pendingOtps.findByEmail(EMAIL)).thenReturn(Optional.of(pending));
        doThrow(failure).when(limiter).requireAllowed(POLICY, DIMENSION, EMAIL);
        assertThatThrownBy(() -> service.resendOtp(resend())).isSameAs(failure);
        var order = inOrder(pendingOtps, pending, limiter);
        order.verify(pendingOtps).findByEmail(EMAIL);
        order.verify(pending).getExpiresAt();
        order.verify(limiter).requireAllowed(POLICY, DIMENSION, EMAIL);
        assertUnchanged(pending, expiry);
        verifyNoMoreInteractions(pendingOtps, limiter);
        verifyNoInteractions(generator, passwords, mail, users, cache);
    }

    @Test
    void eligibleResendDenialLeavesAllPendingFieldsUnchanged() {
        resendGuardFailure(RateLimitException.rejected(1000));
    }

    @Test
    void eligibleResendUnavailableLeavesAllPendingFieldsUnchanged() {
        resendGuardFailure(RateLimitException.unavailable());
    }

    @Test
    void allowedResendOrdersBudgetBeforeGenerationHashMutationSaveAndMail() {
        var pending = pending(true);
        when(pendingOtps.findByEmail(EMAIL)).thenReturn(Optional.of(pending));
        generatedOtp();
        OffsetDateTime before = OffsetDateTime.now();
        service.resendOtp(resend());
        OffsetDateTime after = OffsetDateTime.now();
        var order = inOrder(pendingOtps, pending, limiter, generator, passwords, mail);
        order.verify(pendingOtps).findByEmail(EMAIL);
        order.verify(pending).getExpiresAt();
        order.verify(limiter).requireAllowed(POLICY, DIMENSION, EMAIL);
        order.verify(generator).generate();
        order.verify(passwords).encode("123456");
        order.verify(pending).setOtpHash("new-otp-hash");
        order.verify(pending).setExpiresAt(any());
        order.verify(pending).setAttempts(0);
        order.verify(pendingOtps).save(pending);
        order.verify(mail).sendRegistrationOtp(EMAIL, "123456");
        assertThat(pending.getId()).isEqualTo(42L);
        assertThat(pending.getPasswordHash()).isEqualTo("password-hash");
        assertThat(pending.getOtpHash()).isEqualTo("new-otp-hash");
        assertThat(pending.getExpiresAt()).isBetween(before.plusMinutes(5), after.plusMinutes(5));
        assertThat(pending.getAttempts()).isZero();
        verifyNoMoreInteractions(limiter, mail, passwords);
        verifyNoInteractions(users, cache);
    }

    @Test
    void bothRoutesUseSameRootCanonicalBudgetUnderTurkishDefault() {
        var failure = RateLimitException.rejected(1000);
        doThrow(failure).when(limiter).requireAllowed(POLICY, DIMENSION, EMAIL);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThatThrownBy(() -> service.requestOtp(request())).isSameAs(failure);
            when(pendingOtps.findByEmail(EMAIL)).thenReturn(Optional.of(pending(true)));
            assertThatThrownBy(() -> service.resendOtp(resend())).isSameAs(failure);
        } finally {
            Locale.setDefault(previous);
        }
        verify(limiter, times(2)).requireAllowed(POLICY, DIMENSION, EMAIL);
        verifyNoMoreInteractions(limiter);
        verifyNoInteractions(generator, passwords, mail);
    }

    @Test
    void requestSmtpFailurePropagatesAfterOneBudgetChargeWithoutRetryOrRefund() {
        generatedOtp();
        when(passwords.encode(PASSWORD)).thenReturn("password-hash");
        var failure = new IllegalStateException("SMTP unavailable");
        doThrow(failure).when(mail).sendRegistrationOtp(EMAIL, "123456");
        assertThatThrownBy(() -> service.requestOtp(request())).isSameAs(failure);
        var order = inOrder(limiter, pendingOtps, mail);
        order.verify(limiter).requireAllowed(POLICY, DIMENSION, EMAIL);
        order.verify(pendingOtps).save(any(RegistrationOtp.class));
        order.verify(mail).sendRegistrationOtp(EMAIL, "123456");
        verify(generator).generate();
        verifyNoMoreInteractions(limiter, mail, generator);
        verify(users, never()).save(any());
    }

    @Test
    void resendSmtpFailurePropagatesAfterOneBudgetChargeWithoutRetryOrRefund() {
        when(pendingOtps.findByEmail(EMAIL)).thenReturn(Optional.of(pending(true)));
        generatedOtp();
        var failure = new IllegalStateException("SMTP unavailable");
        doThrow(failure).when(mail).sendRegistrationOtp(EMAIL, "123456");
        assertThatThrownBy(() -> service.resendOtp(resend())).isSameAs(failure);
        var order = inOrder(limiter, pendingOtps, mail);
        order.verify(limiter).requireAllowed(POLICY, DIMENSION, EMAIL);
        order.verify(pendingOtps).save(any(RegistrationOtp.class));
        order.verify(mail).sendRegistrationOtp(EMAIL, "123456");
        verify(generator).generate();
        verifyNoMoreInteractions(limiter, mail, generator);
        verifyNoInteractions(users);
    }

    @Test
    void globalBypassAllowsRequestThroughCoreWithoutRedisAndKeepsResendBusinessRule() {
        var properties = new RateLimitProperties(false, null, null, null, null, null, null);
        var metrics = mock(RateLimitMetrics.class);
        var core = spy(new RateLimitService(properties, null, () -> {
            throw new AssertionError("Disabled limiter must not access Redis");
        }, metrics));
        doAnswer(call -> {
            core.requireAllowed(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            return null;
        }).when(limiter).requireAllowed(POLICY, DIMENSION, EMAIL);
        generatedOtp();
        when(passwords.encode(PASSWORD)).thenReturn("password-hash");
        service.requestOtp(request());
        verify(core).requireAllowed(POLICY, DIMENSION, EMAIL);
        verify(core).consume(POLICY, DIMENSION, EMAIL);
        verify(mail).sendRegistrationOtp(EMAIL, "123456");
        clearInvocations(limiter);
        when(pendingOtps.findByEmail(EMAIL)).thenReturn(Optional.of(pending(false)));
        assertStatus(() -> service.resendOtp(resend()), HttpStatus.BAD_REQUEST,
                "Current OTP is still valid. Please wait until it expires before requesting a new one.");
        verifyNoInteractions(limiter);
        verifyNoMoreInteractions(mail);
    }

    @Test
    void verifyUsesDbLockAndPersistsFiveFailuresThenBlocksCorrectCodeWithoutEmailGuard() throws Exception {
        var pending = pending(false);
        pending.setAttempts(0);
        clearInvocations(pending);
        when(pendingOtps.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(pending));
        var wrong = new VerifyRegistrationOtpRequest(EMAIL, "000000");
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertStatus(() -> service.verifyOtp(wrong), attempt == 5 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_REQUEST,
                    attempt == 5 ? "Too many invalid OTP attempts. Please request a new code." : "OTP is invalid.");
            assertThat(pending.getAttempts()).isEqualTo(attempt);
        }
        assertStatus(() -> service.verifyOtp(new VerifyRegistrationOtpRequest(EMAIL, "123456")),
                HttpStatus.TOO_MANY_REQUESTS, "Too many invalid OTP attempts. Please request a new code.");
        var order = inOrder(pendingOtps, passwords, pending);
        order.verify(pendingOtps).findByEmailForUpdate(EMAIL);
        order.verify(passwords).matches("000000", "old-otp-hash");
        order.verify(pending).setAttempts(1);
        order.verify(pendingOtps).save(pending);
        verify(pendingOtps, times(6)).findByEmailForUpdate(EMAIL);
        verify(pendingOtps, times(5)).save(pending);
        verify(passwords, times(5)).matches("000000", "old-otp-hash");
        verifyNoMoreInteractions(passwords);
        verifyNoInteractions(limiter, users, generator, mail, cache);
        assertThat(StudentRegistrationService.class.getMethod("verifyOtp", VerifyRegistrationOtpRequest.class)
                .getAnnotation(Transactional.class).noRollbackFor()).containsExactly(ApiException.class);
    }

    @Test
    void verifyExpiredOtpStopsWithoutPasswordOrUserWorkAndWithoutEmailGuard() {
        when(pendingOtps.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(pending(true)));
        assertStatus(() -> service.verifyOtp(new VerifyRegistrationOtpRequest(EMAIL, "123456")),
                HttpStatus.BAD_REQUEST, "OTP has expired.");
        verify(pendingOtps).findByEmailForUpdate(EMAIL);
        verifyNoMoreInteractions(pendingOtps);
        verifyNoInteractions(limiter, passwords, users, generator, mail, cache);
    }

    @Test
    void verifySuccessAloneCreatesStudentThenDeletesPendingAndEvictsCacheWithoutEmailGuard() {
        var pending = pending(false);
        when(pendingOtps.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(pending));
        when(passwords.matches("123456", "old-otp-hash")).thenReturn(true);
        service.verifyOtp(new VerifyRegistrationOtpRequest(" I@Example.COM ", "123456"));
        var order = inOrder(pendingOtps, passwords, users, cache);
        order.verify(pendingOtps).findByEmailForUpdate(EMAIL);
        order.verify(passwords).matches("123456", "old-otp-hash");
        order.verify(users).findByEmail(EMAIL);
        var saved = org.mockito.ArgumentCaptor.forClass(User.class);
        order.verify(users).save(saved.capture());
        order.verify(pendingOtps).delete(pending);
        order.verify(cache).evictAdminDashboard();
        assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("password-hash");
        assertThat(saved.getValue().getRole()).isEqualTo(UserRole.STUDENT);
        assertThat(saved.getValue().getAccountOrigin()).isEqualTo(AccountOrigin.SELF_REGISTERED);
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(saved.getValue().isLocked()).isFalse();
        assertThat(saved.getValue().isMustChangePassword()).isFalse();
        verifyNoInteractions(limiter, generator, mail);
    }
}
