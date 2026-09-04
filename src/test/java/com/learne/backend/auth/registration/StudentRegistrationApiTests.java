package com.learne.backend.auth.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learne.backend.auth.registration.service.OtpGenerator;
import com.learne.backend.auth.registration.service.RegistrationMailService;
import com.learne.backend.domain.auth.RegistrationOtp;
import com.learne.backend.domain.auth.RegistrationOtpRepository;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.testsupport.TestDatabaseCleaner;
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

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class StudentRegistrationApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegistrationOtpRepository registrationOtpRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CapturingRegistrationMailService mailService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
        mailService.clear();
    }

    @Test
    void requestOtpStoresPendingRegistrationAndSendsEmailWithoutReturningOtp() throws Exception {
        mockMvc.perform(post("/api/auth/register/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Nguyen Van A",
                                  "email": "Student@Example.com",
                                  "password": "Password123",
                                  "confirmPassword": "Password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Registration OTP has been sent to your email."))
                .andExpect(jsonPath("$.otp").doesNotExist());

        RegistrationOtp pending = registrationOtpRepository.findByEmail("student@example.com").orElseThrow();
        assertThat(pending.getFullName()).isEqualTo("Nguyen Van A");
        assertThat(pending.getPasswordHash()).isNotEqualTo("Password123");
        assertThat(passwordEncoder.matches("Password123", pending.getPasswordHash())).isTrue();
        assertThat(pending.getOtpHash()).isNotEqualTo("123456");
        assertThat(passwordEncoder.matches("123456", pending.getOtpHash())).isTrue();
        assertThat(pending.getExpiresAt()).isAfter(OffsetDateTime.now().plusMinutes(4));
        assertThat(mailService.sentMessages()).containsExactly(new SentOtp("student@example.com", "123456"));
    }

    @Test
    void requestOtpRejectsExistingUserEmail() throws Exception {
        User user = new User();
        user.setEmail("student@example.com");
        user.setFullName("Existing Student");
        user.setPasswordHash(passwordEncoder.encode("Password123"));
        user.setRole(UserRole.STUDENT);
        user.setActive(true);
        userRepository.save(user);

        mockMvc.perform(post("/api/auth/register/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Nguyen Van A",
                                  "email": "student@example.com",
                                  "password": "Password123",
                                  "confirmPassword": "Password123"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email is already registered."));

        assertThat(registrationOtpRepository.findByEmail("student@example.com")).isEmpty();
        assertThat(mailService.sentMessages()).isEmpty();
    }

    @Test
    void requestOtpRejectsWeakPasswordAndPasswordMismatch() throws Exception {
        mockMvc.perform(post("/api/auth/register/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Nguyen Van A",
                                  "email": "student@example.com",
                                  "password": "password",
                                  "confirmPassword": "password"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Password must be at least 8 characters and contain both letters and numbers."));

        mockMvc.perform(post("/api/auth/register/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Nguyen Van A",
                                  "email": "student@example.com",
                                  "password": "Password123",
                                  "confirmPassword": "Password456"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Password confirmation does not match."));
    }

    @Test
    void verifyOtpCreatesStudentAndDeletesPendingRegistration() throws Exception {
        savePendingRegistration("student@example.com", "Nguyen Van A", "Password123", "123456", OffsetDateTime.now().plusMinutes(5));

        mockMvc.perform(post("/api/auth/register/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com",
                                  "otp": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Registration completed successfully."))
                .andExpect(jsonPath("$.otp").doesNotExist());

        User student = userRepository.findByEmail("student@example.com").orElseThrow();
        assertThat(student.getFullName()).isEqualTo("Nguyen Van A");
        assertThat(student.getRole()).isEqualTo(UserRole.STUDENT);
        assertThat(student.isActive()).isTrue();
        assertThat(student.isLocked()).isFalse();
        assertThat(student.isMustChangePassword()).isFalse();
        assertThat(passwordEncoder.matches("Password123", student.getPasswordHash())).isTrue();
        assertThat(registrationOtpRepository.findByEmail("student@example.com")).isEmpty();
    }

    @Test
    void verifyOtpRejectsWrongOtpAndTracksAttemptOnly() throws Exception {
        savePendingRegistration("student@example.com", "Nguyen Van A", "Password123", "123456", OffsetDateTime.now().plusMinutes(5));

        mockMvc.perform(post("/api/auth/register/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com",
                                  "otp": "000000"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("OTP is invalid."));

        assertThat(userRepository.findByEmail("student@example.com")).isEmpty();
        RegistrationOtp pending = registrationOtpRepository.findByEmail("student@example.com").orElseThrow();
        assertThat(pending.getAttempts()).isEqualTo(1);
    }

    @Test
    void verifyOtpRejectsExpiredOtpAndMissingPendingRegistration() throws Exception {
        savePendingRegistration("expired@example.com", "Nguyen Van A", "Password123", "123456", OffsetDateTime.now().minusSeconds(1));

        mockMvc.perform(post("/api/auth/register/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "expired@example.com",
                                  "otp": "123456"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("OTP has expired."));

        mockMvc.perform(post("/api/auth/register/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "missing@example.com",
                                  "otp": "123456"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No pending registration found for this email."));
    }

    @Test
    void resendOtpRejectsMissingPendingRegistrationAndCurrentOtpStillValid() throws Exception {
        mockMvc.perform(post("/api/auth/register/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "missing@example.com"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No pending registration found for this email."));

        savePendingRegistration("student@example.com", "Nguyen Van A", "Password123", "123456", OffsetDateTime.now().plusMinutes(5));

        mockMvc.perform(post("/api/auth/register/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current OTP is still valid. Please wait until it expires before requesting a new one."));

        assertThat(mailService.sentMessages()).isEmpty();
    }

    @Test
    void resendOtpUpdatesExpiredOtpAndSendsNewEmailWithoutReturningOtp() throws Exception {
        savePendingRegistration("student@example.com", "Nguyen Van A", "Password123", "654321", OffsetDateTime.now().minusSeconds(1));

        mockMvc.perform(post("/api/auth/register/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("A new registration OTP has been sent to your email."))
                .andExpect(jsonPath("$.otp").doesNotExist());

        RegistrationOtp pending = registrationOtpRepository.findByEmail("student@example.com").orElseThrow();
        assertThat(passwordEncoder.matches("123456", pending.getOtpHash())).isTrue();
        assertThat(passwordEncoder.matches("654321", pending.getOtpHash())).isFalse();
        assertThat(pending.getAttempts()).isZero();
        assertThat(pending.getExpiresAt()).isAfter(OffsetDateTime.now().plusMinutes(4));
        assertThat(mailService.sentMessages()).containsExactly(new SentOtp("student@example.com", "123456"));
    }

    private void savePendingRegistration(String email, String fullName, String password, String otp, OffsetDateTime expiresAt) {
        RegistrationOtp pending = new RegistrationOtp();
        pending.setEmail(email);
        pending.setFullName(fullName);
        pending.setPasswordHash(passwordEncoder.encode(password));
        pending.setOtpHash(passwordEncoder.encode(otp));
        pending.setExpiresAt(expiresAt);
        pending.setAttempts(0);
        registrationOtpRepository.save(pending);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        OtpGenerator otpGenerator() {
            return () -> "123456";
        }

        @Bean
        @Primary
        CapturingRegistrationMailService capturingRegistrationMailService() {
            return new CapturingRegistrationMailService();
        }
    }

    record SentOtp(String email, String otp) {
    }

    static class CapturingRegistrationMailService extends RegistrationMailService {
        private final List<SentOtp> sentMessages = new ArrayList<>();

        CapturingRegistrationMailService() {
            super(null);
        }

        @Override
        public void sendRegistrationOtp(String email, String otp) {
            sentMessages.add(new SentOtp(email, otp));
        }

        List<SentOtp> sentMessages() {
            return List.copyOf(sentMessages);
        }

        void clear() {
            sentMessages.clear();
        }
    }
}
