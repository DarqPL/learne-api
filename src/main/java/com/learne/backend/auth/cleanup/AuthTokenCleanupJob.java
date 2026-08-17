package com.learne.backend.auth.cleanup;

import com.learne.backend.domain.auth.PasswordResetTokenRepository;
import com.learne.backend.domain.auth.RefreshTokenRepository;
import com.learne.backend.domain.auth.RegistrationOtpRepository;
import java.time.OffsetDateTime;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuthTokenCleanupJob {

    private final RegistrationOtpRepository registrationOtpRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    AuthTokenCleanupJob(
            RegistrationOtpRepository registrationOtpRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            RefreshTokenRepository refreshTokenRepository) {
        this.registrationOtpRepository = registrationOtpRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupExpiredAuthArtifacts() {
        OffsetDateTime now = OffsetDateTime.now();
        registrationOtpRepository.deleteByExpiresAtBefore(now);
        passwordResetTokenRepository.deleteByExpiresAtBeforeOrUsedAtIsNotNull(now);
        refreshTokenRepository.deleteByExpiresAtBeforeOrRevokedTrue(now);
    }
}
