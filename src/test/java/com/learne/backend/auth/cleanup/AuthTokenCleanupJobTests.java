package com.learne.backend.auth.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.domain.auth.PasswordResetToken;
import com.learne.backend.domain.auth.PasswordResetTokenRepository;
import com.learne.backend.domain.auth.RefreshToken;
import com.learne.backend.domain.auth.RefreshTokenRepository;
import com.learne.backend.domain.auth.RegistrationOtp;
import com.learne.backend.domain.auth.RegistrationOtpRepository;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class AuthTokenCleanupJobTests {

    @Autowired
    private AuthTokenCleanupJob cleanupJob;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RegistrationOtpRepository registrationOtpRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        deleteLearningAndContentRows();
        passwordResetTokenRepository.deleteAll();
        registrationOtpRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private void deleteLearningAndContentRows() {
        jdbcTemplate.update("delete from question_answers");
        jdbcTemplate.update("delete from quiz_attempts");
        jdbcTemplate.update("delete from learning_activities");
        jdbcTemplate.update("delete from user_stats");
        jdbcTemplate.update("delete from lesson_progress");
        jdbcTemplate.update("delete from course_enrollments");
        jdbcTemplate.update("delete from learning_path_items");
        jdbcTemplate.update("delete from learning_paths");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from lessons");
        jdbcTemplate.update("delete from topics");
        jdbcTemplate.update("delete from courses");
        jdbcTemplate.update("delete from teacher_profiles");
    }

    @Test
    void cleanupDeletesOnlyExpiredRegistrationOtps() {
        saveRegistrationOtp("expired@example.com", OffsetDateTime.now().minusMinutes(1));
        saveRegistrationOtp("valid@example.com", OffsetDateTime.now().plusMinutes(1));

        cleanupJob.cleanupExpiredAuthArtifacts();

        assertThat(registrationOtpRepository.findAll())
                .singleElement()
                .satisfies(otp -> assertThat(otp.getEmail()).isEqualTo("valid@example.com"));
    }

    @Test
    void cleanupDeletesExpiredAndUsedPasswordResetTokensOnly() {
        User user = saveUser("student@example.com");
        savePasswordResetToken(user, "expired", OffsetDateTime.now().minusMinutes(1), null);
        savePasswordResetToken(user, "used", OffsetDateTime.now().plusMinutes(1), OffsetDateTime.now());
        savePasswordResetToken(user, "valid", OffsetDateTime.now().plusMinutes(1), null);

        cleanupJob.cleanupExpiredAuthArtifacts();

        assertThat(passwordResetTokenRepository.findAll())
                .singleElement()
                .satisfies(token -> assertThat(token.getTokenHash()).isEqualTo("valid"));
    }

    @Test
    void cleanupDeletesExpiredAndRevokedRefreshTokensOnly() {
        User user = saveUser("student@example.com");
        saveRefreshToken(user, "expired", OffsetDateTime.now().minusMinutes(1), false);
        saveRefreshToken(user, "revoked", OffsetDateTime.now().plusMinutes(1), true);
        saveRefreshToken(user, "valid", OffsetDateTime.now().plusMinutes(1), false);

        cleanupJob.cleanupExpiredAuthArtifacts();

        assertThat(refreshTokenRepository.findAll())
                .singleElement()
                .satisfies(token -> assertThat(token.getTokenHash()).isEqualTo("valid"));
    }

    private User saveUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setFullName("Student User");
        user.setPasswordHash(passwordEncoder.encode("Password123"));
        user.setRole(UserRole.STUDENT);
        user.setActive(true);
        return userRepository.save(user);
    }

    private void saveRegistrationOtp(String email, OffsetDateTime expiresAt) {
        RegistrationOtp otp = new RegistrationOtp();
        otp.setEmail(email);
        otp.setFullName("Student User");
        otp.setPasswordHash("password-hash");
        otp.setOtpHash("otp-" + email);
        otp.setExpiresAt(expiresAt);
        registrationOtpRepository.save(otp);
    }

    private void savePasswordResetToken(User user, String tokenHash, OffsetDateTime expiresAt, OffsetDateTime usedAt) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(tokenHash);
        token.setExpiresAt(expiresAt);
        token.setUsedAt(usedAt);
        token.setCreatedAt(OffsetDateTime.now());
        passwordResetTokenRepository.save(token);
    }

    private void saveRefreshToken(User user, String tokenHash, OffsetDateTime expiresAt, boolean revoked) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(tokenHash);
        token.setExpiresAt(expiresAt);
        token.setRevoked(revoked);
        token.setRevokedAt(revoked ? OffsetDateTime.now() : null);
        token.setCreatedAt(OffsetDateTime.now());
        refreshTokenRepository.save(token);
    }
}
