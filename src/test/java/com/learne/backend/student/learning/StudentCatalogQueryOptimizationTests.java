package com.learne.backend.student.learning;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.monetization.entity.CourseEntitlement;
import com.learne.backend.monetization.enums.CourseEntitlementSource;
import com.learne.backend.monetization.repository.CourseEntitlementRepository;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.time.Instant;
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
class StudentCatalogQueryOptimizationTests {
    @Autowired private UserRepository userRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private CourseEntitlementRepository courseEntitlementRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CacheManager cacheManager;
    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, cacheManager);
    }

    @Test
    void catalogAccessStatusesArePreservedForFreeOwnedAndLockedCourses() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        createApprovedCourse(teacherToken, adminToken, "Free A1", "A1", null, null);
        long ownedCourseId = createApprovedCourse(teacherToken, adminToken, "Owned Paid", "A2", "PAID", 250000L);
        createApprovedCourse(teacherToken, adminToken, "Locked Paid", "B1", "PAID", 300000L);
        seedCourseEntitlement(student, ownedCourseId);

        mockMvc.perform(get("/api/student/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Free A1')].accessStatus").value(hasItem("FREE")))
                .andExpect(jsonPath("$[?(@.name == 'Owned Paid')].accessStatus").value(hasItem("OWNED")))
                .andExpect(jsonPath("$[?(@.name == 'Locked Paid')].accessStatus").value(hasItem("LOCKED")));
    }

    private long createApprovedCourse(String teacherToken, String adminToken, String name, String level,
                                      String pricingType, Long proposedPriceAmountVnd) throws Exception {
        long courseId = createCourse(teacherToken, name, level, pricingType, proposedPriceAmountVnd);
        long topicId = createTopic(teacherToken, courseId);
        long lessonId = createLesson(teacherToken, topicId);
        createMultipleChoiceQuestion(teacherToken, lessonId);
        approveCourse(teacherToken, adminToken, courseId);
        return courseId;
    }

    private long createCourse(String teacherToken, String name, String level, String pricingType,
                              Long proposedPriceAmountVnd) throws Exception {
        String pricingJson = pricingType == null ? "" : ",\n  \"pricingType\": \"%s\",\n  \"proposedPriceAmountVnd\": %d"
                .formatted(pricingType, proposedPriceAmountVnd);
        MvcResult result = mockMvc.perform(post("/api/teacher/content/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "description": "Catalog optimization course",
                                  "type": "COMPREHENSIVE",
                                  "level": "%s",
                                  "orderIndex": 1
                                  %s
                                }
                                """.formatted(name, level, pricingJson)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createTopic(String teacherToken, long courseId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/teacher/content/courses/%d/topics".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Catalog Topic",
                                  "description": "Catalog topic",
                                  "orderIndex": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createLesson(String teacherToken, long topicId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/teacher/content/topics/%d/lessons".formatted(topicId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Catalog Lesson",
                                  "description": "Catalog lesson",
                                  "content": "Practice catalog vocabulary.",
                                  "skillType": "READING",
                                  "orderIndex": 1,
                                  "xpReward": 20,
                                  "placementTest": false
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void createMultipleChoiceQuestion(String teacherToken, long lessonId) throws Exception {
        mockMvc.perform(post("/api/teacher/content/lessons/%d/questions".formatted(lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "MULTIPLE_CHOICE",
                                  "questionText": "Choose the correct answer.",
                                  "explanation": "A is correct.",
                                  "knowledgePoint": "catalog",
                                  "orderIndex": 1,
                                  "difficulty": "easy",
                                  "xpReward": 5,
                                  "options": [
                                    { "optionText": "A", "correct": true, "orderIndex": 1 },
                                    { "optionText": "B", "correct": false, "orderIndex": 2 }
                                  ]
                                }
                                """))
                .andExpect(status().isOk());
    }

    private void approveCourse(String teacherToken, String adminToken, long courseId) throws Exception {
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private void seedCourseEntitlement(User student, long courseId) {
        CourseEntitlement entitlement = new CourseEntitlement();
        entitlement.setUser(student);
        entitlement.setCourse(courseRepository.findById(courseId).orElseThrow());
        entitlement.setSource(CourseEntitlementSource.COURSE_PURCHASE);
        entitlement.setStartsAt(Instant.now().minusSeconds(60));
        courseEntitlementRepository.save(entitlement);
    }

    private User saveUser(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(role == UserRole.TEACHER ? "Teacher User" : role.name() + " User");
        user.setPasswordHash(passwordEncoder.encode("Password123"));
        user.setRole(role);
        user.setActive(true);
        user.setLocked(false);
        user.setMustChangePassword(false);
        return userRepository.save(user);
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
