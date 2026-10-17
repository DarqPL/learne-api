package com.learne.backend.auth.session.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.learne.backend.domain.auth.*;
import com.learne.backend.ratelimit.RateLimitException;
import com.learne.backend.shared.api.ApiException;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthSessionServiceTests {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final JwtService jwt = mock(JwtService.class);
    private final RefreshTokenService refresh = mock(RefreshTokenService.class);
    private final LoginAttemptService attempts = mock(LoginAttemptService.class);
    private final User user = mock(User.class);
    private final AuthSessionService service = new AuthSessionService(users, passwords, mock(PasswordPolicy.class), jwt, refresh, attempts);
    private static final String EMAIL = "i@example.com";

    @BeforeEach
    void executeLockCallback() {
        when(attempts.withEmailLock(anyString(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
    }

    private void knownUser(boolean correctPassword) {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(user.getPasswordHash()).thenReturn("hash");
        when(passwords.matches("password", "hash")).thenReturn(correctPassword);
    }

    private void assertStatus(HttpStatus status) {
        assertThatThrownBy(() -> service.login(EMAIL, "password", "ip", "agent"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(status));
    }

    @Test
    void successfulLoginOrdersCheckQueryPasswordStatusClearRefreshThenJwtWithRootNormalization() {
        knownUser(true);
        when(user.isActive()).thenReturn(true);
        when(user.getRole()).thenReturn(UserRole.STUDENT);
        when(refresh.issue(user, "ip", "agent")).thenReturn(new RefreshTokenService.IssuedRefreshToken("raw", null));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(service.login(" I@Example.COM ", "password", "ip", "agent").refreshToken()).isEqualTo("raw");
        } finally { Locale.setDefault(previous); }
        var order = inOrder(attempts, users, passwords, user, refresh, jwt);
        order.verify(attempts).withEmailLock(eq(EMAIL), any());
        order.verify(attempts).assertCanAttempt(EMAIL);
        order.verify(users).findByEmail(EMAIL);
        order.verify(user).getPasswordHash();
        order.verify(passwords).matches("password", "hash");
        order.verify(user).isActive();
        order.verify(user).isLocked();
        order.verify(attempts).clear(EMAIL);
        order.verify(refresh).issue(user, "ip", "agent");
        order.verify(jwt).createAccessToken(user);
        verify(attempts, never()).recordFailure(any());
    }

    @Test
    void unknownEmailRecordsFailureBeforeUnauthorizedWithoutPasswordOrTokens() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
        assertStatus(HttpStatus.UNAUTHORIZED);
        var order = inOrder(attempts, users);
        order.verify(attempts).assertCanAttempt(EMAIL);
        order.verify(users).findByEmail(EMAIL);
        order.verify(attempts).recordFailure(EMAIL);
        verify(attempts, never()).clear(any());
        verifyNoInteractions(passwords, refresh, jwt);
    }

    @Test
    void wrongPasswordRecordsFailureBeforeUnauthorizedWithoutCheckingAccountStatus() {
        knownUser(false);
        assertStatus(HttpStatus.UNAUTHORIZED);
        var order = inOrder(attempts, passwords);
        order.verify(passwords).matches("password", "hash");
        order.verify(attempts).recordFailure(EMAIL);
        verify(user, never()).isActive();
        verify(user, never()).isLocked();
        verify(attempts, never()).clear(any());
        verifyNoInteractions(refresh, jwt);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void correctPasswordOnInactiveOrLockedAccountIsForbiddenWithoutClearOrTokens(boolean active) {
        knownUser(true);
        when(user.isActive()).thenReturn(active);
        if (active) when(user.isLocked()).thenReturn(true);
        assertStatus(HttpStatus.FORBIDDEN);
        verify(passwords).matches("password", "hash");
        verify(attempts, never()).recordFailure(any());
        verify(attempts, never()).clear(any());
        verifyNoInteractions(refresh, jwt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"check", "failure", "clear"})
    void redisUnavailableAtAnyStagePropagatesWithoutIssuingTokens(String stage) {
        var error = RateLimitException.unavailable();
        if (stage.equals("check")) doThrow(error).when(attempts).assertCanAttempt(EMAIL);
        else {
            knownUser(stage.equals("clear"));
            if (stage.equals("clear")) {
                when(user.isActive()).thenReturn(true);
                doThrow(error).when(attempts).clear(EMAIL);
            } else doThrow(error).when(attempts).recordFailure(EMAIL);
        }
        assertThatThrownBy(() -> service.login(EMAIL, "password", "ip", "agent")).isSameAs(error);
        verifyNoInteractions(refresh, jwt);
        if (stage.equals("check")) verifyNoInteractions(users, passwords);
    }

    @Test
    void rejectedCheckPreventsQueryAndPasswordWork() {
        var error = RateLimitException.rejected(1000);
        doThrow(error).when(attempts).assertCanAttempt(EMAIL);
        assertThatThrownBy(() -> service.login(EMAIL, "password", "ip", "agent")).isSameAs(error);
        verifyNoInteractions(users, passwords, refresh, jwt);
    }
}
