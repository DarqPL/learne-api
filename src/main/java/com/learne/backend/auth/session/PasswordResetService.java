package com.learne.backend.auth.session;

import com.learne.backend.auth.session.dto.ConfirmPasswordResetRequest;
import com.learne.backend.auth.session.dto.PasswordResetRequest;
import com.learne.backend.domain.auth.PasswordPolicy;
import com.learne.backend.domain.auth.PasswordResetToken;
import com.learne.backend.domain.auth.PasswordResetTokenRepository;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.shared.api.ApiException;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PasswordResetService {

    static final String REQUEST_MESSAGE = "If the email is registered, password reset instructions have been sent.";

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final PasswordResetTokenGenerator tokenGenerator;
    private final PasswordResetMailService mailService;
    private final RefreshTokenService refreshTokenService;

    PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            PasswordResetTokenGenerator tokenGenerator,
            PasswordResetMailService mailService,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.tokenGenerator = tokenGenerator;
        this.mailService = mailService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    void requestReset(PasswordResetRequest request) {
        String email = normalizeEmail(request.email());
        userRepository.findByEmail(email)
                .filter(user -> user.isActive() && !user.isLocked())
                .ifPresent(this::createAndSendToken);
    }

    @Transactional(noRollbackFor = ApiException.class)
    void confirmReset(ConfirmPasswordResetRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Password reset token is invalid."));
        PasswordResetToken resetToken = passwordResetTokenRepository.findTopByUserAndUsedAtIsNullOrderByCreatedAtDesc(user)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Password reset token is invalid."));

        if (resetToken.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password reset token has expired.");
        }
        if (!passwordEncoder.matches(request.token(), resetToken.getTokenHash())) {
            resetToken.setAttempts(resetToken.getAttempts() + 1);
            passwordResetTokenRepository.save(resetToken);
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password reset token is invalid.");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New password must be different from current password.");
        }

        passwordPolicy.validate(request.newPassword(), request.confirmNewPassword());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);

        resetToken.setUsedAt(OffsetDateTime.now());
        passwordResetTokenRepository.save(resetToken);
        refreshTokenService.revokeAllActiveTokens(user);
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
        mailService.sendPasswordResetToken(user.getEmail(), token);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }
}
