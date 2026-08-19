package com.learne.backend.admin.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.RefreshTokenRepository;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class AdminUserApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private CapturingTeacherInvitationMailService teacherInvitationMailService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        teacherInvitationMailService.clear();
    }

    @Test
    void lockUserRequiresAdminRole() throws Exception {
        User student = saveUser("student@example.com", "Password123", UserRole.STUDENT);

        mockMvc.perform(post("/api/admin/users/%d/lock".formatted(student.getId())))
                .andExpect(status().isUnauthorized());

        String studentToken = loginAndGetAccessToken("student@example.com", "Password123");

        mockMvc.perform(post("/api/admin/users/%d/lock".formatted(student.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanLockUserAndRevokeAllRefreshTokens() throws Exception {
        User admin = saveUser("admin@example.com", "Password123", UserRole.ADMIN);
        User student = saveUser("student@example.com", "Password123", UserRole.STUDENT);
        Cookie studentRefreshCookie = loginAndGetRefreshCookie("student@example.com", "Password123");
        loginAndGetRefreshCookie("student@example.com", "Password123");
        String adminToken = loginAndGetAccessToken("admin@example.com", "Password123");

        assertThat(refreshTokenRepository.findAllByUserAndRevokedFalse(student)).hasSize(2);

        mockMvc.perform(post("/api/admin/users/%d/lock".formatted(student.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User locked successfully."));

        User lockedStudent = userRepository.findById(student.getId()).orElseThrow();
        assertThat(lockedStudent.isLocked()).isTrue();
        assertThat(lockedStudent.getLockedAt()).isNotNull();
        assertThat(refreshTokenRepository.findAllByUserAndRevokedFalse(lockedStudent)).isEmpty();

        mockMvc.perform(post("/api/auth/refresh").cookie(studentRefreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token is invalid."));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Account is not allowed to sign in."));
    }

    @Test
    void adminCannotLockSelfAndMissingUserReturnsNotFound() throws Exception {
        User admin = saveUser("admin@example.com", "Password123", UserRole.ADMIN);
        String adminToken = loginAndGetAccessToken("admin@example.com", "Password123");

        mockMvc.perform(post("/api/admin/users/%d/lock".formatted(admin.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Admin cannot lock their own account."));

        mockMvc.perform(post("/api/admin/users/999999/lock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found."));
    }

    @Test
    void unlockUserRequiresAdminRole() throws Exception {
        User student = saveLockedUser("student@example.com", "Password123", UserRole.STUDENT);

        mockMvc.perform(post("/api/admin/users/%d/unlock".formatted(student.getId())))
                .andExpect(status().isUnauthorized());

        saveUser("another-student@example.com", "Password123", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken("another-student@example.com", "Password123");

        mockMvc.perform(post("/api/admin/users/%d/unlock".formatted(student.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanUnlockUserAndUserCanLoginAgain() throws Exception {
        saveUser("admin@example.com", "Password123", UserRole.ADMIN);
        User student = saveLockedUser("student@example.com", "Password123", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken("admin@example.com", "Password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Account is not allowed to sign in."));

        mockMvc.perform(post("/api/admin/users/%d/unlock".formatted(student.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User unlocked successfully."));

        User unlockedStudent = userRepository.findById(student.getId()).orElseThrow();
        assertThat(unlockedStudent.isLocked()).isFalse();
        assertThat(unlockedStudent.getLockedAt()).isNull();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(student.getId()));
    }

    @Test
    void unlockMissingUserReturnsNotFound() throws Exception {
        saveUser("admin@example.com", "Password123", UserRole.ADMIN);
        String adminToken = loginAndGetAccessToken("admin@example.com", "Password123");

        mockMvc.perform(post("/api/admin/users/999999/unlock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found."));
    }

    @Test
    void createTeacherRequiresAdminRole() throws Exception {
        saveUser("student@example.com", "Password123", UserRole.STUDENT);

        mockMvc.perform(post("/api/admin/teachers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "teacher@example.com",
                                  "fullName": "Teacher User"
                                }
                                """))
                .andExpect(status().isUnauthorized());

        String studentToken = loginAndGetAccessToken("student@example.com", "Password123");

        mockMvc.perform(post("/api/admin/teachers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "teacher@example.com",
                                  "fullName": "Teacher User"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateTeacherWithTemporaryPasswordAndMustChangePassword() throws Exception {
        saveUser("admin@example.com", "Password123", UserRole.ADMIN);
        String adminToken = loginAndGetAccessToken("admin@example.com", "Password123");

        mockMvc.perform(post("/api/admin/teachers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": " Teacher@Example.com ",
                                  "fullName": "Teacher User"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("teacher@example.com"))
                .andExpect(jsonPath("$.fullName").value("Teacher User"))
                .andExpect(jsonPath("$.role").value("TEACHER"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.temporaryPassword").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User teacher = userRepository.findByEmail("teacher@example.com").orElseThrow();
        assertThat(teacher.getRole()).isEqualTo(UserRole.TEACHER);
        assertThat(teacher.isActive()).isTrue();
        assertThat(teacher.isLocked()).isFalse();
        assertThat(teacher.isMustChangePassword()).isTrue();
        assertThat(passwordEncoder.matches("TempPassword123", teacher.getPasswordHash())).isTrue();
        assertThat(teacherInvitationMailService.sentMessages())
                .containsExactly(new SentTeacherInvitation("teacher@example.com", "Teacher User", "TempPassword123"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "teacher@example.com",
                                  "password": "TempPassword123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("TEACHER"))
                .andExpect(jsonPath("$.user.mustChangePassword").value(true));
    }

    @Test
    void createTeacherRejectsDuplicateEmail() throws Exception {
        saveUser("admin@example.com", "Password123", UserRole.ADMIN);
        saveUser("teacher@example.com", "Password123", UserRole.TEACHER);
        String adminToken = loginAndGetAccessToken("admin@example.com", "Password123");

        mockMvc.perform(post("/api/admin/teachers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "Teacher@Example.com",
                                  "fullName": "Teacher User"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email is already registered."));

        assertThat(teacherInvitationMailService.sentMessages()).isEmpty();
    }

    private User saveUser(String email, String password, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(role.name() + " User");
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        user.setActive(true);
        user.setLocked(false);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }

    private User saveLockedUser(String email, String password, UserRole role) {
        User user = saveUser(email, password, role);
        user.setLocked(true);
        user.setLockedAt(OffsetDateTime.now());
        return userRepository.save(user);
    }

    private String loginAndGetAccessToken(String email, String password) throws Exception {
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
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
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
        TemporaryPasswordGenerator temporaryPasswordGenerator() {
            return () -> "TempPassword123";
        }

        @Bean
        @Primary
        CapturingTeacherInvitationMailService capturingTeacherInvitationMailService() {
            return new CapturingTeacherInvitationMailService();
        }
    }

    record SentTeacherInvitation(String email, String fullName, String temporaryPassword) {
    }

    static class CapturingTeacherInvitationMailService extends TeacherInvitationMailService {
        private final List<SentTeacherInvitation> sentMessages = new ArrayList<>();

        CapturingTeacherInvitationMailService() {
            super(null);
        }

        @Override
        public void sendTeacherInvitation(String email, String fullName, String temporaryPassword) {
            sentMessages.add(new SentTeacherInvitation(email, fullName, temporaryPassword));
        }

        List<SentTeacherInvitation> sentMessages() {
            return List.copyOf(sentMessages);
        }

        void clear() {
            sentMessages.clear();
        }
    }
}
