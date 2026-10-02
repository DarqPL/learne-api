package com.learne.backend.admin.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
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
class AdminDashboardApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheManager cacheManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, cacheManager);
    }

    @Test
    void adminDashboardRequiresAdminRoleAndReturnsSystemOverview() throws Exception {
        saveUser("admin@example.com", "Password123", UserRole.ADMIN);
        saveUser("student@example.com", "Password123", UserRole.STUDENT);
        saveUser("teacher@example.com", "Password123", UserRole.TEACHER);

        mockMvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isUnauthorized());

        String studentToken = loginAndGetAccessToken("student@example.com", "Password123");

        mockMvc.perform(get("/api/admin/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        String adminToken = loginAndGetAccessToken("admin@example.com", "Password123");

        mockMvc.perform(get("/api/admin/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.totalStudents").value(1))
                .andExpect(jsonPath("$.overview.totalTeachers").value(1))
                .andExpect(jsonPath("$.leaderboard").isArray());
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
