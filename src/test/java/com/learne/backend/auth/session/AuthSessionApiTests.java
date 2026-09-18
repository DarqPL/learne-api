package com.learne.backend.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.RefreshToken;
import com.learne.backend.domain.auth.RefreshTokenRepository;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class AuthSessionApiTests {

    private static final String REFRESH_COOKIE_NAME = "refresh_token";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void loginReturnsAccessTokenAndHttpOnlyRefreshCookieWithoutExposingRefreshTokenInJson() throws Exception {
        User student = saveUser("student@example.com", "Password123", UserRole.STUDENT, true, false, false);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "Student@Example.com",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", not(emptyOrNullString())))
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.user.id").value(student.getId()))
                .andExpect(jsonPath("$.user.email").value("student@example.com"))
                .andExpect(jsonPath("$.user.fullName").value("Student User"))
                .andExpect(jsonPath("$.user.role").value("STUDENT"))
                .andExpect(jsonPath("$.user.mustChangePassword").value(false))
                .andExpect(cookie().exists(REFRESH_COOKIE_NAME))
                .andExpect(cookie().httpOnly(REFRESH_COOKIE_NAME, true))
                .andExpect(cookie().path(REFRESH_COOKIE_NAME, "/api/auth"))
                .andExpect(cookie().maxAge(REFRESH_COOKIE_NAME, 604800))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("SameSite=Lax")))
                .andReturn();

        String rawRefreshToken = result.getResponse().getCookie(REFRESH_COOKIE_NAME).getValue();
        RefreshToken savedToken = refreshTokenRepository.findAll().getFirst();
        assertThat(savedToken.getUser().getId()).isEqualTo(student.getId());
        assertThat(savedToken.getTokenHash()).isNotEqualTo(rawRefreshToken);
        assertThat(savedToken.isRevoked()).isFalse();
        assertThat(savedToken.getExpiresAt()).isAfter(OffsetDateTime.now().plusDays(6));
    }

    @Test
    void loginRejectsInvalidCredentialsWithoutCreatingRefreshToken() throws Exception {
        saveUser("student@example.com", "Password123", UserRole.STUDENT, true, false, false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com",
                                  "password": "WrongPassword123"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));

        assertThat(refreshTokenRepository.findAll()).isEmpty();
    }

    @Test
    void loginRejectsAttemptsAfterFiveFailedPasswordsForNormalizedEmail() throws Exception {
        saveUser("limited-login@example.com", "Password123", UserRole.STUDENT, true, false, false);

        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "Limited-Login@Example.com",
                                      "password": "WrongPassword123"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid email or password."));
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "limited-login@example.com",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many failed login attempts. Please try again later."));

        assertThat(refreshTokenRepository.findAll()).isEmpty();
    }

    @Test
    void successfulLoginClearsFailedAttemptCount() throws Exception {
        saveUser("cleared-login@example.com", "Password123", UserRole.STUDENT, true, false, false);

        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "cleared-login@example.com",
                                      "password": "WrongPassword123"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "cleared-login@example.com",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isOk());

        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "cleared-login@example.com",
                                      "password": "WrongPassword123"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "cleared-login@example.com",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void loginRejectsInactiveOrLockedAccounts() throws Exception {
        saveUser("inactive@example.com", "Password123", UserRole.STUDENT, false, false, false);
        saveUser("locked@example.com", "Password123", UserRole.STUDENT, true, true, false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "inactive@example.com",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Account is not allowed to sign in."));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "locked@example.com",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Account is not allowed to sign in."));
    }

    @Test
    void refreshRotatesRefreshCookieAndRevokesThePreviousToken() throws Exception {
        User student = saveUser("student@example.com", "Password123", UserRole.STUDENT, true, false, false);
        Cookie firstCookie = loginAndGetRefreshCookie("student@example.com", "Password123");
        RefreshToken firstToken = refreshTokenRepository.findAll().getFirst();

        MvcResult result = mockMvc.perform(post("/api/auth/refresh").cookie(firstCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", not(emptyOrNullString())))
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.user.email").value("student@example.com"))
                .andExpect(cookie().exists(REFRESH_COOKIE_NAME))
                .andExpect(cookie().httpOnly(REFRESH_COOKIE_NAME, true))
                .andReturn();

        Cookie secondCookie = result.getResponse().getCookie(REFRESH_COOKIE_NAME);
        assertThat(secondCookie.getValue()).isNotEqualTo(firstCookie.getValue());

        RefreshToken revokedFirstToken = refreshTokenRepository.findById(firstToken.getId()).orElseThrow();
        assertThat(revokedFirstToken.isRevoked()).isTrue();
        assertThat(revokedFirstToken.getRevokedAt()).isNotNull();
        assertThat(revokedFirstToken.getReplacedByToken()).isNotNull();

        RefreshToken activeToken = refreshTokenRepository.findByUserAndRevokedFalse(student).orElseThrow();
        assertThat(activeToken.getTokenHash()).isNotEqualTo(secondCookie.getValue());
    }

    @Test
    void reuseOfRevokedRefreshTokenRevokesAllActiveTokensForTheUser() throws Exception {
        saveUser("student@example.com", "Password123", UserRole.STUDENT, true, false, false);
        Cookie firstCookie = loginAndGetRefreshCookie("student@example.com", "Password123");
        Cookie secondCookie = refreshAndGetRefreshCookie(firstCookie);

        mockMvc.perform(post("/api/auth/refresh").cookie(firstCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token is invalid."));

        assertThat(refreshTokenRepository.findAll())
                .allSatisfy(token -> assertThat(token.isRevoked()).isTrue());

        mockMvc.perform(post("/api/auth/refresh").cookie(secondCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token is invalid."));
    }

    @Test
    void logoutRevokesCurrentRefreshTokenAndClearsCookie() throws Exception {
        saveUser("student@example.com", "Password123", UserRole.STUDENT, true, false, false);
        Cookie refreshCookie = loginAndGetRefreshCookie("student@example.com", "Password123");

        mockMvc.perform(post("/api/auth/logout").cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully."))
                .andExpect(cookie().maxAge(REFRESH_COOKIE_NAME, 0));

        assertThat(refreshTokenRepository.findAll())
                .singleElement()
                .satisfies(token -> assertThat(token.isRevoked()).isTrue());
    }

    @Test
    void protectedMeEndpointRequiresValidAccessToken() throws Exception {
        saveUser("student@example.com", "Password123", UserRole.STUDENT, true, false, false);

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());

        String accessToken = loginAndGetAccessToken("student@example.com", "Password123");

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email").value("student@example.com"))
                .andExpect(jsonPath("$.role").value("STUDENT"));
    }

    @Test
    void changePasswordRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "Password123",
                                  "newPassword": "NewPassword123",
                                  "confirmNewPassword": "NewPassword123"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePasswordUpdatesPasswordClearsMustChangePasswordAndRevokesRefreshTokens() throws Exception {
        User teacher = saveUser("teacher@example.com", "Password123", UserRole.TEACHER, true, false, true);
        Cookie refreshCookie = loginAndGetRefreshCookie("teacher@example.com", "Password123");
        String accessToken = loginAndGetAccessToken("teacher@example.com", "Password123");

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "Password123",
                                  "newPassword": "NewPassword123",
                                  "confirmNewPassword": "NewPassword123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed successfully."));

        User updatedTeacher = userRepository.findById(teacher.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("Password123", updatedTeacher.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches("NewPassword123", updatedTeacher.getPasswordHash())).isTrue();
        assertThat(updatedTeacher.isMustChangePassword()).isFalse();
        assertThat(refreshTokenRepository.findAllByUserAndRevokedFalse(updatedTeacher)).isEmpty();

        mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token is invalid."));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "teacher@example.com",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isUnauthorized());

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

    @Test
    void changePasswordRejectsIncorrectCurrentPassword() throws Exception {
        saveUser("student@example.com", "Password123", UserRole.STUDENT, true, false, false);
        String accessToken = loginAndGetAccessToken("student@example.com", "Password123");

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "WrongPassword123",
                                  "newPassword": "NewPassword123",
                                  "confirmNewPassword": "NewPassword123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is incorrect."));
    }

    @Test
    void changePasswordRejectsInvalidNewPasswordRequests() throws Exception {
        saveUser("student@example.com", "Password123", UserRole.STUDENT, true, false, false);
        String accessToken = loginAndGetAccessToken("student@example.com", "Password123");

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "Password123",
                                  "newPassword": "NewPassword123",
                                  "confirmNewPassword": "DifferentPassword123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Password confirmation does not match."));

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "Password123",
                                  "newPassword": "password",
                                  "confirmNewPassword": "password"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Password must be at least 8 characters and contain both letters and numbers."));

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "Password123",
                                  "newPassword": "Password123",
                                  "confirmNewPassword": "Password123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("New password must be different from current password."));
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
        return result.getResponse().getCookie(REFRESH_COOKIE_NAME);
    }

    private Cookie refreshAndGetRefreshCookie(Cookie refreshCookie) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie(REFRESH_COOKIE_NAME);
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
}
