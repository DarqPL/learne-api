package com.learne.backend.auth.session.service;

import com.learne.backend.auth.session.dto.ConfirmPasswordResetRequest;
import com.learne.backend.auth.session.dto.PasswordResetRequest;
import com.learne.backend.domain.auth.PasswordPolicy;
import com.learne.backend.domain.auth.PasswordResetToken;
import com.learne.backend.domain.auth.PasswordResetTokenRepository;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.ratelimit.RateLimitDimension;
import com.learne.backend.ratelimit.RateLimitService;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Service
public class PasswordResetService {

    public static final String REQUEST_MESSAGE = "Nếu email hợp lệ, yêu cầu đặt lại mật khẩu đã được tiếp nhận. Vui lòng kiểm tra hộp thư.";
    private static final int MAX_OTP_ATTEMPTS = 5;
    private static final String TOO_MANY_OTP_ATTEMPTS_MESSAGE = "Too many invalid OTP attempts. Please request a new code.";

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final PasswordResetTokenGenerator tokenGenerator;
    private final PasswordResetMailService mailService;
    private final RefreshTokenService refreshTokenService;
    private final RateLimitService rateLimitService;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            PasswordResetTokenGenerator tokenGenerator,
            PasswordResetMailService mailService,
            RefreshTokenService refreshTokenService,
            RateLimitService rateLimitService
    ) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.tokenGenerator = tokenGenerator;
        this.mailService = mailService;
        this.refreshTokenService = refreshTokenService;
        this.rateLimitService = rateLimitService;
    }

    @Transactional
    public void requestReset(PasswordResetRequest request) {
        String email = normalizeEmail(request.email());
        rateLimitService.requireAllowed("password-reset-request-email", RateLimitDimension.EMAIL, email);
        userRepository.findByEmail(email)
                .filter(user -> user.isActive() && !user.isLocked())
                .ifPresent(this::createAndSendToken);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void confirmReset(ConfirmPasswordResetRequest request) {
        // Serialize confirms with a fresh user read: user -> reset tokens -> refresh sessions.
        User user = userRepository.findByEmailForUpdate(normalizeEmail(request.email()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Password reset token is invalid."));
        PasswordResetToken resetToken = passwordResetTokenRepository.findLatestUnusedByUserForUpdate(user, PageRequest.of(0, 1)).stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Password reset token is invalid."));

        if (resetToken.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password reset token has expired.");
        }
        if (resetToken.getAttempts() >= MAX_OTP_ATTEMPTS) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_OTP_ATTEMPTS_MESSAGE);
        }
        if (!passwordEncoder.matches(request.token(), resetToken.getTokenHash())) {
            int attempts = resetToken.getAttempts() + 1;
            resetToken.setAttempts(attempts);
            passwordResetTokenRepository.save(resetToken);
            if (attempts >= MAX_OTP_ATTEMPTS) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_OTP_ATTEMPTS_MESSAGE);
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password reset token is invalid.");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New password must be different from current password.");
        }

        passwordPolicy.validate(request.newPassword(), request.confirmNewPassword());
        String passwordHash = passwordEncoder.encode(request.newPassword());
        try {
            user.setPasswordHash(passwordHash);
            user.setMustChangePassword(false);
            userRepository.save(user);

            OffsetDateTime usedAt = OffsetDateTime.now();
            resetToken.setUsedAt(usedAt);
            passwordResetTokenRepository.save(resetToken);
            passwordResetTokenRepository.markAllUnusedByUserAsUsed(user, usedAt);
            refreshTokenService.revokeAllActiveTokens(user);
        } catch (RuntimeException failure) {
            // noRollbackFor preserves wrong-code attempts above, never partial credential changes.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw failure;
        }
    }

    private void createAndSendToken(User user) {
        String token = tokenGenerator.generate();
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setUser(user);
        resetToken.setTokenHash(passwordEncoder.encode(token));
        resetToken.setExpiresAt(OffsetDateTime.now().plusMinutes(15));
        resetToken.setAttempts(0);
        resetToken.setCreatedAt(OffsetDateTime.now());
        passwordResetTokenRepository.save(resetToken);
        mailService.sendPasswordResetToken(resetToken, token);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
