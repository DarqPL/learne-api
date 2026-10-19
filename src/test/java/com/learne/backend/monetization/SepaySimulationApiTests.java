package com.learne.backend.monetization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import com.learne.backend.testsupport.CourseKnowledgePointClientTestConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.payments.sepay.enabled=true",
        "app.payments.sepay.mock-enabled=true",
        "app.payments.sepay.bank-code=Vietcombank",
        "app.payments.sepay.bank-name=Vietcombank",
        "app.payments.sepay.account-number=0010000000355",
        "app.payments.sepay.account-name=CONG TY LEARNE"
})
@Import(CourseKnowledgePointClientTestConfiguration.class)
@org.springframework.test.context.bean.override.mockito.MockitoBean(types = {com.learne.backend.monetization.service.PaymentConfirmationMailService.class,
        com.learne.backend.admin.content.service.ReviewDecisionService.class})
class SepaySimulationApiTests extends com.learne.backend.testsupport.SubmissionHttpPostgresSupport {
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
    void adminCanSimulateSepayPaymentByReferenceAndAmountWhenEnabled() throws Exception {
        User admin = saveUser("simulate-admin@example.com", UserRole.ADMIN);
        PendingCourseOrder pending = createPendingSepayCourseOrder("simulate-enabled");
        String adminToken = loginAndGetAccessToken(admin.getEmail());

        mockMvc.perform(post("/api/admin/payments/sepay/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simulationRequest(pending.paymentReference(), pending.amountVnd(), 900001L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(orderStatus(pending.orderId())).isEqualTo("PAID");
        assertThat(activeEntitlementCount(pending.studentId(), pending.courseId())).isEqualTo(1);
        assertThat(sepayEventStatus(900001L)).isEqualTo("PROCESSED");
    }

    @Test
    void studentCannotSimulateSepayPaymentWhenEnabled() throws Exception {
        PendingCourseOrder pending = createPendingSepayCourseOrder("simulate-student");
        String studentToken = loginAndGetAccessToken("student-simulate-student@example.com");

        mockMvc.perform(post("/api/admin/payments/sepay/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simulationRequest(pending.paymentReference(), pending.amountVnd(), 900002L)))
                .andExpect(status().isForbidden());

        assertThat(orderStatus(pending.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(activeEntitlementCount(pending.studentId(), pending.courseId())).isZero();
    }

    @Test
    void simulatingAlreadyPaidOrderIsIdempotent() throws Exception {
        User admin = saveUser("simulate-idempotent-admin@example.com", UserRole.ADMIN);
        PendingCourseOrder pending = createPendingSepayCourseOrder("simulate-idempotent");
        String adminToken = loginAndGetAccessToken(admin.getEmail());

        simulatePayment(adminToken, pending.paymentReference(), pending.amountVnd(), 900003L);
        simulatePayment(adminToken, pending.paymentReference(), pending.amountVnd(), 900003L);

        assertThat(orderStatus(pending.orderId())).isEqualTo("PAID");
        assertThat(activeEntitlementCount(pending.studentId(), pending.courseId())).isEqualTo(1);
        assertThat(sepayEventCount(900003L)).isEqualTo(1);
    }

    @Test
    void simulationWithWrongAmountCreatesFailedEventAndDoesNotSettleOrder() throws Exception {
        User admin = saveUser("simulate-internal-admin@example.com", UserRole.ADMIN);
        PendingCourseOrder pending = createPendingSepayCourseOrder("simulate-wrong-amount");
        String adminToken = loginAndGetAccessToken(admin.getEmail());

        mockMvc.perform(post("/api/admin/payments/sepay/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simulationRequest(pending.paymentReference(), pending.amountVnd() - 1L, 900004L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient amount or amount mismatch."));

        assertThat(orderStatus(pending.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(sepayEventStatus(900004L)).isEqualTo("FAILED");
    }

    @Test
    void expiredSepayOrderCannotBeSimulatedAsSuccessfulPayment() throws Exception {
        User admin = saveUser("simulate-expired-admin@example.com", UserRole.ADMIN);
        PendingCourseOrder pending = createPendingSepayCourseOrder("simulate-expired-status");
        jdbcTemplate.update("update orders set status = 'EXPIRED' where id = ?", pending.orderId());
        String adminToken = loginAndGetAccessToken(admin.getEmail());

        mockMvc.perform(post("/api/admin/payments/sepay/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simulationRequest(pending.paymentReference(), pending.amountVnd(), 900005L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Order is not pending payment."));

        assertThat(orderStatus(pending.orderId())).isEqualTo("EXPIRED");
        assertThat(activeEntitlementCount(pending.studentId(), pending.courseId())).isZero();
    }

    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @ActiveProfiles("test")
    @AutoConfigureMockMvc
    @SpringBootTest(properties = {
            "app.payments.sepay.enabled=true",
            "app.payments.sepay.mock-enabled=false",
            "app.payments.sepay.bank-code=Vietcombank",
            "app.payments.sepay.bank-name=Vietcombank",
            "app.payments.sepay.account-number=0010000000355",
            "app.payments.sepay.account-name=CONG TY LEARNE"
    })
    @Import(CourseKnowledgePointClientTestConfiguration.class)
    @org.springframework.test.context.bean.override.mockito.MockitoBean(types = com.learne.backend.admin.content.service.ReviewDecisionService.class)
    class DisabledSimulationTests extends com.learne.backend.testsupport.SubmissionHttpPostgresSupport {
        @Test
        void simulationIsRejectedWhenDisabled() throws Exception {
            User admin = saveUser("simulate-disabled-admin@example.com", UserRole.ADMIN);
            PendingCourseOrder pending = createPendingSepayCourseOrder("simulate-disabled");
            String adminToken = loginAndGetAccessToken(admin.getEmail());

            mockMvc.perform(post("/api/admin/payments/sepay/simulate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(simulationRequest(pending.paymentReference(), pending.amountVnd(), 900006L)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Payment simulation is disabled."));

            assertThat(orderStatus(pending.orderId())).isEqualTo("PENDING_PAYMENT");
            assertThat(activeEntitlementCount(pending.studentId(), pending.courseId())).isZero();
        }
    }

    private void simulatePayment(String adminToken, String paymentReference, long amountVnd, long sepayTransactionId) throws Exception {
        mockMvc.perform(post("/api/admin/payments/sepay/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simulationRequest(paymentReference, amountVnd, sepayTransactionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private String simulationRequest(String paymentReference, long amountVnd, long sepayTransactionId) {
        return """
                {
                  "paymentReference": "%s",
                  "transferAmountVnd": %d,
                  "sepayTransactionId": %d
                }
                """.formatted(paymentReference, amountVnd, sepayTransactionId);
    }

    private PendingCourseOrder createPendingSepayCourseOrder(String suffix) throws Exception {
        User admin = saveUser("admin-" + suffix + "@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher-" + suffix + "@example.com", UserRole.TEACHER);
        User student = saveUser("student-" + suffix + "@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "SEPAY Simulation Course " + suffix, "A2", 250000L);

        MvcResult checkout = mockMvc.perform(post("/api/student/checkout/course/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentProvider").value("SEPAY"))
                .andExpect(jsonPath("$.paymentReference").isNotEmpty())
                .andReturn();
        JsonNode body = objectMapper.readTree(checkout.getResponse().getContentAsString());
        return new PendingCourseOrder(
                body.get("id").asLong(),
                body.get("paymentReference").asText(),
                body.get("finalAmountVnd").asLong(),
                student.getId(),
                courseId);
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
        submitAndProcess(mockMvc, teacherToken, courseId);

        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.priceStatus").value("APPROVED"));
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
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String orderStatus(long orderId) {
        return jdbcTemplate.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private String sepayEventStatus(long sepayTransactionId) {
        return jdbcTemplate.queryForObject(
                "select status from sepay_webhook_events where sepay_transaction_id = ?",
                String.class,
                sepayTransactionId);
    }

    private int sepayEventCount(long sepayTransactionId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from sepay_webhook_events where sepay_transaction_id = ?",
                Integer.class,
                sepayTransactionId);
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
                """, Integer.class, userId, courseId, java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
    }

    private record PendingCourseOrder(long orderId, String paymentReference, long amountVnd, Long studentId, long courseId) {}
}
