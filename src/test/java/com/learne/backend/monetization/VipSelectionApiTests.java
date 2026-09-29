package com.learne.backend.monetization;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.entity.OrderItem;
import com.learne.backend.monetization.entity.Subscription;
import com.learne.backend.monetization.enums.OrderItemProductType;
import com.learne.backend.monetization.enums.OrderStatus;
import com.learne.backend.monetization.enums.OrderType;
import com.learne.backend.monetization.enums.PaymentProvider;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.monetization.enums.SubscriptionStatus;
import com.learne.backend.monetization.repository.OrderRepository;
import com.learne.backend.monetization.repository.SubscriptionRepository;
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
class VipSelectionApiTests {
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;
    @Autowired private OrderRepository orderRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void proSubscriptionAllowsEnrollmentInApprovedPaidA2Course() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        saveSubscription(student, PlanCode.PRO);
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 300000L);

        mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId));
    }

    @Test
    void vipSubscriptionDoesNotOpenUnselectedPaidCourse() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        saveSubscription(student, PlanCode.VIP);
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 300000L);

        mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Course access is required."));
    }

    @Test
    void vipSelectionOpensSelectedPaidCourse() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        saveSubscription(student, PlanCode.VIP);
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 300000L);

        mockMvc.perform(post("/api/student/vip/selections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":%d}".formatted(courseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId))
                .andExpect(jsonPath("$.level").value("A2"));

        mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId));
    }

    @Test
    void vipA2QuotaRejectsThirdActiveSelection() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        saveSubscription(student, PlanCode.VIP);
        long firstCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium 1", "A2", 300000L);
        long secondCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium 2", "A2", 300000L);
        long thirdCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium 3", "A2", 300000L);

        selectVipCourse(studentToken, firstCourseId);
        selectVipCourse(studentToken, secondCourseId);

        mockMvc.perform(post("/api/student/vip/selections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":%d}".formatted(thirdCourseId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("VIP quota exceeded for A2."));
    }

    @Test
    void vipB1QuotaRejectsSecondActiveSelection() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        saveSubscription(student, PlanCode.VIP);
        long firstCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English B1 Premium 1", "B1", 300000L);
        long secondCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English B1 Premium 2", "B1", 300000L);

        selectVipCourse(studentToken, firstCourseId);

        mockMvc.perform(post("/api/student/vip/selections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":%d}".formatted(secondCourseId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("VIP quota exceeded for B1."));
    }

    @Test
    void vipSelectionRequiresCourseId() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken(student.getEmail());
        saveSubscription(student, PlanCode.VIP);

        mockMvc.perform(post("/api/student/vip/selections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("courseId is required."));

        mockMvc.perform(post("/api/student/vip/selections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("courseId is required."));
    }

    private void selectVipCourse(String studentToken, long courseId) throws Exception {
        mockMvc.perform(post("/api/student/vip/selections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":%d}".formatted(courseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId));
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

    private void saveSubscription(User student, PlanCode planCode) {
        Instant now = Instant.now();
        Subscription subscription = new Subscription();
        subscription.setUser(student);
        subscription.setPlanCode(planCode);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartsAt(now.minusSeconds(60));
        subscription.setEndsAt(now.plusSeconds(3600));
        subscription.setOrder(savePaidPlanOrder(student, planCode));
        subscriptionRepository.save(subscription);
    }

    private Order savePaidPlanOrder(User student, PlanCode planCode) {
        Order order = new Order();
        order.setUser(student);
        order.setOrderType(OrderType.PLAN_SUBSCRIPTION);
        order.setStatus(OrderStatus.PAID);
        order.setOriginalAmountVnd(500000L);
        order.setDiscountAmountVnd(0L);
        order.setFinalAmountVnd(500000L);
        order.setPaymentProvider(PaymentProvider.INTERNAL);
        order.setPaidAt(Instant.now());
        order.setExpiresAt(Instant.now().plusSeconds(900));

        OrderItem item = new OrderItem();
        item.setProductType(OrderItemProductType.PLAN);
        item.setPlanCode(planCode);
        item.setItemNameSnapshot(planCode.name());
        item.setUnitAmountVnd(500000L);
        item.setQuantity(1);
        item.setTotalAmountVnd(500000L);
        order.addItem(item);

        return orderRepository.save(order);
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
