package com.learne.backend.auth.session.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.learne.backend.auth.session.dto.PasswordResetRequest;
import com.learne.backend.auth.session.dto.ConfirmPasswordResetRequest;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.domain.auth.*;
import com.learne.backend.ratelimit.*;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTests {
    private static final String POLICY = "password-reset-request-email";
    private static final String EMAIL = "identity@example.com";
    @Mock private UserRepository users;
    @Mock private PasswordResetTokenRepository tokens;
    @Mock private PasswordEncoder encoder;
    @Mock private PasswordPolicy passwordPolicy;
    @Mock private PasswordResetTokenGenerator generator;
    @Mock private PasswordResetMailService mail;
    @Mock private RefreshTokenService refresh;
    @Mock private RateLimitService limiter;
    @InjectMocks private PasswordResetService service;

    private User user() {
        var user = new User();
        user.setEmail(EMAIL);
        return user;
    }

    @ParameterizedTest
    @ValueSource(strings = {"known", "unknown", "inactive", "locked"})
    void consumesCanonicalEmailBeforeLookupForEveryAccountState(String state) {
        var user = user();
        user.setActive(!state.equals("inactive"));
        user.setLocked(state.equals("locked"));
        when(users.findByEmail(EMAIL)).thenReturn(state.equals("unknown") ? Optional.empty() : Optional.of(user));
        if (state.equals("known")) {
            when(generator.generate()).thenReturn("123456");
            when(encoder.encode("123456")).thenReturn("hash");
        }
        var before = OffsetDateTime.now();
        service.requestReset(new PasswordResetRequest("  IDENTITY@EXAMPLE.COM  "));
        var order = inOrder(limiter, users, generator, encoder, tokens, mail);
        order.verify(limiter).requireAllowed(POLICY, RateLimitDimension.EMAIL, EMAIL);
        order.verify(users).findByEmail(EMAIL);
        if (state.equals("known")) {
            order.verify(generator).generate();
            order.verify(encoder).encode("123456");
            var saved = ArgumentCaptor.forClass(PasswordResetToken.class);
            order.verify(tokens).save(saved.capture());
            assertThat(saved.getValue().getUser()).isSameAs(user);
            assertThat(saved.getValue().getTokenHash()).isEqualTo("hash");
            assertThat(saved.getValue().getAttempts()).isZero();
            assertThat(saved.getValue().getExpiresAt()).isBetween(before.plusMinutes(15), OffsetDateTime.now().plusMinutes(15));
            order.verify(mail).sendPasswordResetToken(EMAIL, "123456");
        } else {
            verifyNoInteractions(generator, encoder, tokens, mail);
        }
        verifyNoMoreInteractions(limiter, users, generator, encoder, tokens, mail);
        verifyNoInteractions(passwordPolicy, refresh);
    }

    @Test
    void confirmLocksUserBeforeTokenAndValidatesBeforeMutationThenConsumesBeforeRefresh() {
        var user = confirmFixture();
        service.confirmReset(confirmRequest());
        var order = inOrder(users, tokens, encoder, passwordPolicy, refresh);
        order.verify(users).findByEmailForUpdate(EMAIL);
        order.verify(tokens).findLatestUnusedByUserForUpdate(eq(user), any());
        order.verify(encoder).matches("246801", "otp-hash");
        order.verify(encoder).matches("NewPassword123", "old-hash");
        order.verify(passwordPolicy).validate("NewPassword123", "NewPassword123");
        order.verify(encoder).encode("NewPassword123");
        order.verify(users).save(user);
        var selected = ArgumentCaptor.forClass(PasswordResetToken.class);
        order.verify(tokens).save(selected.capture());
        order.verify(tokens).markAllUnusedByUserAsUsed(user, selected.getValue().getUsedAt());
        order.verify(refresh).revokeAllActiveTokens(user);
        verify(users, never()).findByEmail(anyString());
        verifyNoInteractions(limiter, generator, mail);
    }

    @ParameterizedTest
    @ValueSource(strings = {"consume", "revoke"})
    void postMutationApiExceptionExplicitlyMarksTransactionRollbackOnlyAndRethrowsCause(String stage) {
        confirmFixture();
        var failure = new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "mutation failed");
        if (stage.equals("consume")) doThrow(failure).when(tokens).markAllUnusedByUserAsUsed(any(), any());
        else doThrow(failure).when(refresh).revokeAllActiveTokens(any());
        var status = mock(org.springframework.transaction.TransactionStatus.class);
        try (var tx = mockStatic(org.springframework.transaction.interceptor.TransactionAspectSupport.class)) {
            tx.when(org.springframework.transaction.interceptor.TransactionAspectSupport::currentTransactionStatus).thenReturn(status);
            assertThatThrownBy(() -> service.confirmReset(confirmRequest())).isSameAs(failure);
            verify(status).setRollbackOnly();
        }
    }

    @Test
    void invalidPasswordPolicyNeverConsumesOrMutates() {
        var user = confirmFixture();
        doThrow(new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "invalid password"))
                .when(passwordPolicy).validate(any(), any());
        assertThatThrownBy(() -> service.confirmReset(confirmRequest())).isInstanceOf(ApiException.class);
        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        verify(users, never()).save(any());
        verify(tokens, never()).save(any());
        verify(tokens, never()).markAllUnusedByUserAsUsed(any(), any());
        verifyNoInteractions(refresh);
        verify(encoder, never()).encode(any());
    }

    private ConfirmPasswordResetRequest confirmRequest() {
        return new ConfirmPasswordResetRequest(" IDENTITY@EXAMPLE.COM ", "246801", "NewPassword123", "NewPassword123");
    }

    private User confirmFixture() {
        var user = user();
        user.setPasswordHash("old-hash");
        var token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash("otp-hash");
        token.setExpiresAt(OffsetDateTime.now().plusMinutes(15));
        when(users.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(tokens.findLatestUnusedByUserForUpdate(eq(user), any())).thenReturn(java.util.List.of(token));
        when(encoder.matches("246801", "otp-hash")).thenReturn(true);
        // Policy rejection deliberately does not reach encoding.
        lenient().when(encoder.encode("NewPassword123")).thenReturn("new-hash");
        return user;
    }

    @Test
    void normalizationUsesRootEvenUnderTurkishDefaultLocale() {
        var original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            service.requestReset(new PasswordResetRequest("  IDENTITY@EXAMPLE.COM  "));
            var order = inOrder(limiter, users);
            order.verify(limiter).requireAllowed(POLICY, RateLimitDimension.EMAIL, EMAIL);
            order.verify(users).findByEmail(EMAIL);
        } finally {
            Locale.setDefault(original);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void guardDenialOrUnavailabilityPreventsAllBusinessWork(boolean unavailable) {
        var failure = unavailable ? RateLimitException.unavailable() : RateLimitException.rejected(1000);
        doThrow(failure).when(limiter).requireAllowed(POLICY, RateLimitDimension.EMAIL, EMAIL);
        assertThatThrownBy(() -> service.requestReset(new PasswordResetRequest(EMAIL))).isSameAs(failure);
        verify(limiter).requireAllowed(POLICY, RateLimitDimension.EMAIL, EMAIL);
        verifyNoMoreInteractions(limiter);
        verifyNoInteractions(users, tokens, encoder, passwordPolicy, generator, mail, refresh);
    }

    @Test
    void smtpFailureDoesNotRefundOrRetryReservationOrBusinessWork() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
        when(generator.generate()).thenReturn("123456");
        when(encoder.encode("123456")).thenReturn("hash");
        var failure = new IllegalStateException("SMTP unavailable");
        doThrow(failure).when(mail).sendPasswordResetToken(EMAIL, "123456");
        assertThatThrownBy(() -> service.requestReset(new PasswordResetRequest(EMAIL))).isSameAs(failure);
        var order = inOrder(limiter, users, generator, encoder, tokens, mail);
        order.verify(limiter).requireAllowed(POLICY, RateLimitDimension.EMAIL, EMAIL);
        order.verify(users).findByEmail(EMAIL);
        order.verify(generator).generate();
        order.verify(encoder).encode("123456");
        order.verify(tokens).save(any(PasswordResetToken.class));
        order.verify(mail).sendPasswordResetToken(EMAIL, "123456");
        verifyNoMoreInteractions(limiter, users, generator, encoder, tokens, mail);
        verifyNoInteractions(passwordPolicy, refresh);
    }
}
