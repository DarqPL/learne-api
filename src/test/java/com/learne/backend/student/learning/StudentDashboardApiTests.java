package com.learne.backend.student.learning;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.student.learning.entity.UserStats;
import com.learne.backend.student.learning.repository.UserStatsRepository;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.time.LocalDate;
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
class StudentDashboardApiTests {
    @Autowired private UserRepository userRepository;
    @Autowired private UserStatsRepository userStatsRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void dashboardRequiresStudentRoleAndReturnsLeaderboardOrderedByXpThenStreak() throws Exception {
        User current = saveUser("current@example.com", "Current User", UserRole.STUDENT);
        User top = saveUser("top@example.com", "Top User", UserRole.STUDENT);
        User tieWinner = saveUser("tie@example.com", "Tie User", UserRole.STUDENT);
        User teacher = saveUser("teacher@example.com", "Teacher User", UserRole.TEACHER);
        saveStats(current, 120, 2, 3);
        saveStats(top, 500, 1, 4);
        saveStats(tieWinner, 120, 5, 5);

        mockMvc.perform(get("/api/student/dashboard"))
                .andExpect(status().isUnauthorized());

        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        mockMvc.perform(get("/api/student/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isForbidden());

        String studentToken = loginAndGetAccessToken(current.getEmail());
        mockMvc.perform(get("/api/student/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.totalXp").value(120))
                .andExpect(jsonPath("$.leaderboard", hasSize(3)))
                .andExpect(jsonPath("$.leaderboard[0].fullName").value("Top User"))
                .andExpect(jsonPath("$.leaderboard[1].fullName").value("Tie User"))
                .andExpect(jsonPath("$.leaderboard[2].fullName").value("Current User"));
    }

    private User saveUser(String email, String fullName, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPasswordHash(passwordEncoder.encode("Password123"));
        user.setRole(role);
        user.setActive(true);
        user.setLocked(false);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }

    private void saveStats(User user, int totalXp, int currentStreakDays, int longestStreakDays) {
        UserStats stats = new UserStats();
        stats.setUser(user);
        stats.setTotalXp(totalXp);
        stats.setCurrentStreakDays(currentStreakDays);
        stats.setLongestStreakDays(longestStreakDays);
        stats.setLastActivityDate(LocalDate.now());
        userStatsRepository.save(stats);
    }

    private String loginAndGetAccessToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "Password123"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }
}
