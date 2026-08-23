package com.learne.backend.auth.session.controller;

import com.learne.backend.auth.session.dto.AuthTokenResponse;
import com.learne.backend.auth.session.dto.AuthUserResponse;
import com.learne.backend.auth.session.dto.ChangePasswordRequest;
import com.learne.backend.auth.session.dto.ConfirmPasswordResetRequest;
import com.learne.backend.auth.session.dto.LoginRequest;
import com.learne.backend.auth.session.dto.PasswordResetRequest;
import com.learne.backend.auth.session.service.AuthProperties;
import com.learne.backend.auth.session.service.AuthSessionService;
import com.learne.backend.auth.session.service.PasswordResetService;
import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.shared.api.ApiMessageResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthSessionController {

    private final AuthSessionService authSessionService;
    private final PasswordResetService passwordResetService;
    private final AuthProperties authProperties;

    AuthSessionController(AuthSessionService authSessionService, PasswordResetService passwordResetService, AuthProperties authProperties) {
        this.authSessionService = authSessionService;
        this.passwordResetService = passwordResetService;
        this.authProperties = authProperties;
    }

    @PostMapping("/login")
    ResponseEntity<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        AuthSessionService.LoginResult result = authSessionService.login(
                request.email(),
                request.password(),
                servletRequest.getRemoteAddr(),
                servletRequest.getHeader(HttpHeaders.USER_AGENT)
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken(), authProperties.refreshTokenTtl()).toString())
                .body(result.response());
    }

    @PostMapping("/refresh")
    ResponseEntity<AuthTokenResponse> refresh(HttpServletRequest servletRequest) {
        AuthSessionService.LoginResult result = authSessionService.refresh(
                refreshTokenFromCookie(servletRequest),
                servletRequest.getRemoteAddr(),
                servletRequest.getHeader(HttpHeaders.USER_AGENT)
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken(), authProperties.refreshTokenTtl()).toString())
                .body(result.response());
    }

    @PostMapping("/logout")
    ResponseEntity<ApiMessageResponse> logout(HttpServletRequest servletRequest) {
        String refreshToken = findRefreshTokenCookie(servletRequest);
        if (refreshToken != null) {
            authSessionService.logout(refreshToken);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .body(new ApiMessageResponse("Logged out successfully."));
    }

    @GetMapping("/me")
    AuthUserResponse me(Authentication authentication) {
        return AuthUserResponse.from((User) authentication.getPrincipal());
    }

    @PostMapping("/change-password")
    ApiMessageResponse changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        authSessionService.changePassword((User) authentication.getPrincipal(), request);
        return new ApiMessageResponse("Password changed successfully.");
    }

    @PostMapping("/password-reset/request")
    ApiMessageResponse requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestReset(request);
        return new ApiMessageResponse(PasswordResetService.REQUEST_MESSAGE);
    }

    @PostMapping("/password-reset/confirm")
    ApiMessageResponse confirmPasswordReset(@Valid @RequestBody ConfirmPasswordResetRequest request) {
        passwordResetService.confirmReset(request);
        return new ApiMessageResponse("Password reset successfully.");
    }

    private String refreshTokenFromCookie(HttpServletRequest request) {
        String refreshToken = findRefreshTokenCookie(request);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token is invalid.");
        }
        return refreshToken;
    }

    private String findRefreshTokenCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> authProperties.refreshCookieName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(authProperties.refreshCookieName(), value)
                .httpOnly(true)
                .secure(authProperties.refreshCookieSecure())
                .sameSite(authProperties.refreshCookieSameSite())
                .path(authProperties.refreshCookiePath())
                .maxAge(maxAge)
                .build();
    }
}
