package com.learne.backend.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learne.backend.auth.session.service.PasswordResetMailService;
import com.learne.backend.auth.session.service.PasswordResetTokenGenerator;
import com.learne.backend.domain.auth.PasswordResetToken;
import com.learne.backend.domain.auth.PasswordResetTokenRepository;
import com.learne.backend.domain.auth.RefreshTokenRepository;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class PasswordResetApiTests {

    private static final String REQUEST_MESSAGE = "If the email is registered, password reset instructions have been sent.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private CapturingPasswordResetMailService mailService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
        mailService.clear();
    }

    @Test
    void requestPasswordResetDoesNotLeakMissingEmails() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "missing@example.com"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(REQUEST_MESSAGE));

        assertThat(passwordResetTokenRepository.findAll()).isEmpty();
        assertThat(mailService.sentMessages()).isEmpty();
    }

    @Test
    void requestPasswordResetStoresHashedTokenAndSendsEmailForActiveUnlockedUser() throws Exception {
        User student = saveUser("student@example.com", "Password123", UserRole.STUDENT, true, false, false);

        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "Student@Example.com"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(REQUEST_MESSAGE))
                .andExpect(jsonPath("$.token").doesNotExist());

        PasswordResetToken token = passwordResetTokenRepository.findAll().getFirst();
        assertThat(token.getUser().getId()).isEqualTo(student.getId());
        assertThat(token.getTokenHash()).isNotEqualTo("123456");
        assertThat(token.getExpiresAt()).isAfter(OffsetDateTime.now().plusMinutes(14));
        assertThat(token.getUsedAt()).isNull();
        assertThat(token.getAttempts()).isZero();
        assertThat(mailService.sentMessages()).containsExactly(new SentResetToken("student@example.com", "123456"));
    }

    @Test
    void confirmPasswordResetRejectsInvalidTokenAndTracksAttempt() throws Exception {
        User student = saveUser("student@example.com", "Password123", UserRole.STUDENT, true, false, false);
        savePasswordResetToken(student, "123456", OffsetDateTime.now().plusMinutes(15), null, 0);

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com",
                                  "token": "000000",
                                  "newPassword": "NewPassword123",
                                  "confirmNewPassword": "NewPassword123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Password reset token is invalid."));

        PasswordResetToken token = passwordResetTokenRepository.findAll().getFirst();
        assertThat(token.getAttempts()).isEqualTo(1);
        assertThat(passwordEncoder.matches("Password123", userRepository.findById(student.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void confirmPasswordResetRejectsExpiredToken() throws Exception {
        User student = saveUser("student@example.com", "Password123", UserRole.STUDENT, true, false, false);
        savePasswordResetToken(student, "123456", OffsetDateTime.now().minusSeconds(1), null, 0);

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com",
                                  "token": "123456",
                                  "newPassword": "NewPassword123",
                                  "confirmNewPassword": "NewPassword123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Password reset token has expired."));
    }

    @Test
    void confirmPasswordResetRejectsInvalidNewPasswordRequests() throws Exception {
        User student = saveUser("student@example.com", "Password123", UserRole.STUDENT, true, false, false);
        savePasswordResetToken(student, "123456", OffsetDateTime.now().plusMinutes(15), null, 0);

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com",
                                  "token": "123456",
                                  "newPassword": "NewPassword123",
                                  "confirmNewPassword": "DifferentPassword123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Password confirmation does not match."));

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com",
                                  "token": "123456",
                                  "newPassword": "password",
                                  "confirmNewPassword": "password"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Password must be at least 8 characters and contain both letters and numbers."));

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com",
                                  "token": "123456",
                                  "newPassword": "Password123",
                                  "confirmNewPassword": "Password123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("New password must be different from current password."));
    }

    @Test
    void confirmPasswordResetChangesPasswordMarksTokenUsedAndRevokesRefreshTokens() throws Exception {
        User teacher = saveUser("teacher@example.com", "Password123", UserRole.TEACHER, true, false, true);
        savePasswordResetToken(teacher, "123456", OffsetDateTime.now().plusMinutes(15), null, 0);
        Cookie refreshCookie = loginAndGetRefreshCookie("teacher@example.com", "Password123");

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "teacher@example.com",
                                  "token": "123456",
                                  "newPassword": "NewPassword123",
                                  "confirmNewPassword": "NewPassword123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password reset successfully."));

        User updatedTeacher = userRepository.findById(teacher.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("NewPassword123", updatedTeacher.getPasswordHash())).isTrue();
        assertThat(updatedTeacher.isMustChangePassword()).isFalse();
        assertThat(passwordResetTokenRepository.findAll()).singleElement().satisfies(token -> assertThat(token.getUsedAt()).isNotNull());
        assertThat(refreshTokenRepository.findAllByUserAndRevokedFalse(updatedTeacher)).isEmpty();

        mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token is invalid."));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "teacher@example.com",
                                  "password": "NewPassword123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(false));
    }

    private User saveUser(String email, String password, UserRole role, boolean active, boolean locked, boolean mustChangePassword) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(role.name().charAt(0) + role.name().substring(1).toLowerCase() + " User");
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        user.setActive(active);
        user.setLocked(locked);
        user.setMustChangePassword(mustChangePassword);
        return userRepository.save(user);
    }

    private void savePasswordResetToken(User user, String token, OffsetDateTime expiresAt, OffsetDateTime usedAt, int attempts) {
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setUser(user);
        resetToken.setTokenHash(passwordEncoder.encode(token));
        resetToken.setExpiresAt(expiresAt);
        resetToken.setUsedAt(usedAt);
        resetToken.setAttempts(attempts);
        resetToken.setCreatedAt(OffsetDateTime.now());
        passwordResetTokenRepository.save(resetToken);
    }

    private Cookie loginAndGetRefreshCookie(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie("refresh_token");
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        PasswordResetTokenGenerator passwordResetTokenGenerator() {
            return () -> "123456";
        }

        @Bean
        @Primary
        CapturingPasswordResetMailService capturingPasswordResetMailService() {
            return new CapturingPasswordResetMailService();
        }
    }

    record SentResetToken(String email, String token) {
    }

    static class CapturingPasswordResetMailService extends PasswordResetMailService {
        private final List<SentResetToken> sentMessages = new ArrayList<>();

        CapturingPasswordResetMailService() {
            super(null);
        }

        @Override
        public void sendPasswordResetToken(String email, String token) {
            sentMessages.add(new SentResetToken(email, token));
        }

        List<SentResetToken> sentMessages() {
            return List.copyOf(sentMessages);
        }

        void clear() {
            sentMessages.clear();
        }
    }
}
