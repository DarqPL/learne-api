package com.learne.backend.monetization;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.time.Instant;
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
class CheckoutApiTests {
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void studentCanCreateCourseOrderAndAdminMarkPaidUnlocksCourse() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);

        MvcResult checkoutResult = mockMvc.perform(post("/api/student/checkout/course/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.finalAmountVnd").value(250000))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].courseId").value(courseId))
                .andReturn();
        long orderId = objectMapper.readTree(checkoutResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/admin/orders/%d/mark-paid".formatted(orderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId));
    }

    @Test
    void duplicateCheckoutWhilePendingReturnsBadRequest() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        checkoutCourse(studentToken, courseId);

        mockMvc.perform(post("/api/student/checkout/course/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pending order already exists."));
    }

    @Test
    void markPaidTwiceIsIdempotentAndLeavesOneEntitlement() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        long orderId = checkoutCourse(studentToken, courseId);

        markPaid(adminToken, orderId);
        markPaid(adminToken, orderId);

        org.assertj.core.api.Assertions.assertThat(activeEntitlementCount(student.getId(), courseId)).isEqualTo(1);
    }

    @Test
    void payingLegacyDuplicatePendingOrdersDoesNotCreateDuplicateEntitlements() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        long firstOrderId = checkoutCourse(studentToken, courseId);
        jdbcTemplate.update("update orders set expires_at = ? where id = ?", Instant.now().minusSeconds(1), firstOrderId);
        long secondOrderId = checkoutCourse(studentToken, courseId);
        jdbcTemplate.update("update orders set expires_at = ? where id = ?", Instant.now().plusSeconds(900), firstOrderId);

        markPaid(adminToken, secondOrderId);
        markPaid(adminToken, firstOrderId);

        org.assertj.core.api.Assertions.assertThat(activeEntitlementCount(student.getId(), courseId)).isEqualTo(1);
    }

    @Test
    void expiredPendingOrderMarkPaidReturnsBadRequestAndExpiresOrder() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        long orderId = checkoutCourse(studentToken, courseId);
        jdbcTemplate.update("update orders set expires_at = ? where id = ?", Instant.now().minusSeconds(1), orderId);

        mockMvc.perform(post("/api/admin/orders/%d/mark-paid".formatted(orderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Order has expired."));

        String orderStatus = jdbcTemplate.queryForObject("select status from orders where id = ?", String.class, orderId);
        org.assertj.core.api.Assertions.assertThat(orderStatus).isEqualTo("EXPIRED");
        org.assertj.core.api.Assertions.assertThat(activeEntitlementCount(student.getId(), courseId)).isZero();
    }

    private long createApprovedPaidCourse(String teacherToken, String adminToken, String name, String level,
                                          long proposedPriceAmountVnd) throws Exception {
        long courseId = createCourse(teacherToken, name, level, proposedPriceAmountVnd);
        long topicId = createTopic(teacherToken, courseId);
        long lessonId = createLesson(teacherToken, topicId);
        createMultipleChoiceQuestion(teacherToken, lessonId);
        approveCourse(teacherToken, adminToken, courseId);

        return courseId;
    }

    private long checkoutCourse(String studentToken, long courseId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/student/checkout/course/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void markPaid(String adminToken, long orderId) throws Exception {
        mockMvc.perform(post("/api/admin/orders/%d/mark-paid".formatted(orderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    private int activeEntitlementCount(Long userId, long courseId) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from course_entitlements
                where user_id = ?
                  and course_id = ?
                  and revoked_at is null
                  and starts_at <= ?
                  and (ends_at is null or ends_at > ?)
                """, Integer.class, userId, courseId, Instant.now(), Instant.now());
    }

    private void approveCourse(String teacherToken, String adminToken, long courseId) throws Exception {
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.priceStatus").value("APPROVED"));
    }

    private long createCourse(String teacherToken, String name, String level, long proposedPriceAmountVnd) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/teacher/content/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "description": "Premium English course",
                                  "type": "COMPREHENSIVE",
                                  "level": "%s",
                                  "orderIndex": 1,
                                  "pricingType": "PAID",
                                  "proposedPriceAmountVnd": %d
                                }
                                """.formatted(name, level, proposedPriceAmountVnd)))
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
                                  "name": "Premium Lessons",
                                  "description": "Premium topic",
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
                                  "title": "Premium Lesson",
                                  "content": "Learn premium English content.",
                                  "orderIndex": 1,
                                  "skillType": "READING",
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
                                  "questionText": "Choose the correct greeting.",
                                  "type": "MULTIPLE_CHOICE",
                                  "orderIndex": 1,
                                  "correctAnswer": "Hello",
                                  "options": [
                                    {"optionText": "Hello", "correct": true},
                                    {"optionText": "Banana", "correct": false}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());
    }

    private User saveUser(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(role.name() + " User");
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setActive(true);
        return userRepository.save(user);
    }

    private String loginAndGetAccessToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "Password123!"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }
}
