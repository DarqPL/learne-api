package com.learne.backend.admin.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.cache.CacheKeySupport;
import com.learne.backend.cache.CacheNames;
import com.learne.backend.admin.user.service.TeacherInvitationMailService;
import com.learne.backend.admin.user.service.TemporaryPasswordGenerator;
import com.learne.backend.domain.auth.RefreshTokenRepository;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import com.learne.backend.student.learning.entity.UserStats;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.LearningActivityRepository;
import com.learne.backend.student.learning.repository.LearningPathItemRepository;
import com.learne.backend.student.learning.repository.LearningPathRepository;
import com.learne.backend.student.learning.repository.LessonProgressRepository;
import com.learne.backend.student.learning.repository.QuestionAnswerRepository;
import com.learne.backend.student.learning.repository.QuizAttemptRepository;
import com.learne.backend.student.learning.repository.UserStatsRepository;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.QuestionOptionRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
import com.learne.backend.teacher.profile.entity.TeacherProfile;
import com.learne.backend.teacher.profile.repository.TeacherProfileRepository;
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
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

    @Autowired
    private TeacherProfileRepository teacherProfileRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private LessonRepository lessonRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private QuestionOptionRepository questionOptionRepository;

    @Autowired
    private QuestionAnswerRepository questionAnswerRepository;

    @Autowired
    private QuizAttemptRepository quizAttemptRepository;

    @Autowired
    private LearningActivityRepository learningActivityRepository;

    @Autowired
    private UserStatsRepository userStatsRepository;

    @Autowired
    private LessonProgressRepository lessonProgressRepository;

    @Autowired
    private CourseEnrollmentRepository courseEnrollmentRepository;

    @Autowired
    private LearningPathItemRepository learningPathItemRepository;

    @Autowired
    private LearningPathRepository learningPathRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheManager cacheManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, cacheManager);
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
        cacheManager.getCache(CacheNames.ADMIN_DASHBOARD).put(CacheKeySupport.adminDashboard(), "stale-admin-dashboard");

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
        assertThat(cacheManager.getCache(CacheNames.ADMIN_DASHBOARD).get(CacheKeySupport.adminDashboard())).isNull();
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

    @Test
    void listTeachersRequiresAdminRole() throws Exception {
        User student = saveUser("student@example.com", "Password123", UserRole.STUDENT);

        mockMvc.perform(get("/api/admin/teachers"))
                .andExpect(status().isUnauthorized());

        String studentToken = loginAndGetAccessToken("student@example.com", "Password123");

        mockMvc.perform(get("/api/admin/teachers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanListTeachersWithProfileStatus() throws Exception {
        saveUser("admin@example.com", "Password123", UserRole.ADMIN);
        User teacherWithProfile = saveUser("teacher-a@example.com", "Password123", UserRole.TEACHER);
        User teacherWithoutProfile = saveUser("teacher-b@example.com", "Password123", UserRole.TEACHER);
        User lockedTeacher = saveLockedUser("teacher-c@example.com", "Password123", UserRole.TEACHER);
        saveUser("student@example.com", "Password123", UserRole.STUDENT);

        TeacherProfile profile = new TeacherProfile();
        profile.setUser(teacherWithProfile);
        profile.setBio("Giang vien tieng Anh giao tiep");
        profile.setExpertise("Communication, Grammar");
        profile.setYearsOfExperience(3);
        teacherProfileRepository.save(profile);

        String adminToken = loginAndGetAccessToken("admin@example.com", "Password123");

        mockMvc.perform(get("/api/admin/teachers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.userId == %d)].fullName".formatted(teacherWithProfile.getId())).value("TEACHER User"))
                .andExpect(jsonPath("$[?(@.userId == %d)].locked".formatted(teacherWithProfile.getId())).value(false))
                .andExpect(jsonPath("$[?(@.userId == %d)].mustChangePassword".formatted(teacherWithProfile.getId())).value(false))
                .andExpect(jsonPath("$[?(@.userId == %d)].profileStatus".formatted(teacherWithProfile.getId())).value("ACTIVE"))
                .andExpect(jsonPath("$[?(@.userId == %d)].completed".formatted(teacherWithProfile.getId())).value(true))
                .andExpect(jsonPath("$[?(@.userId == %d)].profileStatus".formatted(teacherWithoutProfile.getId())).value((Object) null))
                .andExpect(jsonPath("$[?(@.userId == %d)].completed".formatted(teacherWithoutProfile.getId())).value(false))
                .andExpect(jsonPath("$[?(@.userId == %d)].locked".formatted(lockedTeacher.getId())).value(true));
    }

    @Test
    void listStudentsRequiresAdminAndReturnsLearningStats() throws Exception {
        saveUser("admin@example.com", "Password123", UserRole.ADMIN);
        User student = saveUser("student@example.com", "Password123", UserRole.STUDENT);
        saveUserStats(student, 250, 4, 6);

        mockMvc.perform(get("/api/admin/students"))
                .andExpect(status().isUnauthorized());

        String adminToken = loginAndGetAccessToken("admin@example.com", "Password123");

        mockMvc.perform(get("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(student.getId()))
                .andExpect(jsonPath("$[0].totalXp").value(250))
                .andExpect(jsonPath("$[0].currentStreakDays").value(4));
    }

    @Test
    void listStudentsDefaultsMissingStatsToZero() throws Exception {
        saveUser("admin@example.com", "Password123", UserRole.ADMIN);
        User student = saveUser("student@example.com", "Password123", UserRole.STUDENT);

        String adminToken = loginAndGetAccessToken("admin@example.com", "Password123");

        mockMvc.perform(get("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(student.getId()))
                .andExpect(jsonPath("$[0].totalXp").value(0))
                .andExpect(jsonPath("$[0].currentStreakDays").value(0))
                .andExpect(jsonPath("$[0].longestStreakDays").value(0))
                .andExpect(jsonPath("$[0].lastActivityDate").doesNotExist())
                .andExpect(jsonPath("$[0].enrollmentCount").value(0))
                .andExpect(jsonPath("$[0].completedLessonCount").value(0));
    }

    @Test
    void getStudentDetailRequiresAdminAndRejectsNonStudentIds() throws Exception {
        saveUser("admin@example.com", "Password123", UserRole.ADMIN);
        User student = saveUser("student@example.com", "Password123", UserRole.STUDENT);
        User teacher = saveUser("teacher@example.com", "Password123", UserRole.TEACHER);
        saveUserStats(student, 250, 4, 6);

        mockMvc.perform(get("/api/admin/students/%d".formatted(student.getId())))
                .andExpect(status().isUnauthorized());

        String adminToken = loginAndGetAccessToken("admin@example.com", "Password123");

        mockMvc.perform(get("/api/admin/students/%d".formatted(student.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(student.getId()))
                .andExpect(jsonPath("$.stats.totalXp").value(250));

        mockMvc.perform(get("/api/admin/students/%d".formatted(teacher.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
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

    private UserStats saveUserStats(User user, int totalXp, int currentStreakDays, int longestStreakDays) {
        UserStats stats = new UserStats();
        stats.setUser(user);
        stats.setTotalXp(totalXp);
        stats.setCurrentStreakDays(currentStreakDays);
        stats.setLongestStreakDays(longestStreakDays);
        return userStatsRepository.save(stats);
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
