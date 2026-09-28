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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
@SpringBootTest(properties = {
        "app.payments.sepay.enabled=true",
        "app.payments.sepay.webhook-auth-mode=HMAC",
        "app.payments.sepay.webhook-secret=test-sepay-secret",
        "app.payments.sepay.bank-code=Vietcombank",
        "app.payments.sepay.bank-name=Vietcombank",
        "app.payments.sepay.account-number=0010000000355",
        "app.payments.sepay.account-name=CONG TY LEARNE"
})
class SepayWebhookApiTests {
    private static final String WEBHOOK_SECRET = "test-sepay-secret";

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
    void validWebhookMarksCourseOrderPaidAndCreatesEntitlement() throws Exception {
        PendingCourseOrder pending = createPendingSepayCourseOrder("valid");
        String body = webhookBody(9001L, pending.paymentReference(), "in", 250000L);

        postWebhook(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(orderStatus(pending.orderId())).isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject("select paid_at is not null from orders where id = ?", Boolean.class, pending.orderId()))
                .isTrue();
        assertThat(activeEntitlementCount(pending.studentId(), pending.courseId())).isEqualTo(1);
        assertThat(eventStatus(9001L)).isEqualTo("PROCESSED");
    }

    @Test
    void replayedWebhookReturnsSuccessWithoutDuplicateFulfillment() throws Exception {
        PendingCourseOrder pending = createPendingSepayCourseOrder("duplicate");
        String body = webhookBody(9002L, pending.paymentReference(), "in", 250000L);

        postWebhook(body).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        postWebhook(body).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));

        assertThat(eventCount(9002L)).isEqualTo(1);
        assertThat(activeEntitlementCount(pending.studentId(), pending.courseId())).isEqualTo(1);
    }

    @Test
    void invalidHmacIsUnauthorizedAndDoesNotMarkPaid() throws Exception {
        PendingCourseOrder pending = createPendingSepayCourseOrder("invalid-hmac");
        String body = webhookBody(9003L, pending.paymentReference(), "in", 250000L);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        mockMvc.perform(post("/api/webhooks/sepay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SePay-Timestamp", timestamp)
                        .header("X-SePay-Signature", "sha256=bad")
                        .content(body))
                .andExpect(status().isUnauthorized());

        assertThat(orderStatus(pending.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(eventCount(9003L)).isZero();
    }

    @Test
    void negativeAmountPayloadIsRejectedBeforeEventPersistence() throws Exception {
        PendingCourseOrder pending = createPendingSepayCourseOrder("negative-amount");
        String body = webhookBody(9008L, pending.paymentReference(), "in", -1L);

        postWebhook(body)
                .andExpect(status().isBadRequest());

        assertThat(orderStatus(pending.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(eventCount(9008L)).isZero();
    }

    @Test
    void insufficientAmountIsLoggedButDoesNotMarkPaid() throws Exception {
        PendingCourseOrder pending = createPendingSepayCourseOrder("short-amount");
        String body = webhookBody(9004L, pending.paymentReference(), "in", 249999L);

        postWebhook(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(orderStatus(pending.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(eventStatus(9004L)).isEqualTo("FAILED");
        assertThat(eventFailureReason(9004L)).containsIgnoringCase("insufficient amount");
    }

    @Test
    void outgoingTransferIsIgnoredAndDoesNotMarkPaid() throws Exception {
        PendingCourseOrder pending = createPendingSepayCourseOrder("outgoing");
        String body = webhookBody(9005L, pending.paymentReference(), "out", 250000L);

        postWebhook(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(orderStatus(pending.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(eventStatus(9005L)).isEqualTo("IGNORED");
    }

    @Test
    void unknownReferenceIsLoggedButDoesNotSettleAnyOrder() throws Exception {
        PendingCourseOrder pending = createPendingSepayCourseOrder("unknown-reference");
        String body = webhookBody(9006L, "LEMISSINGREFERENCE", "in", 250000L);

        postWebhook(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(orderStatus(pending.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(eventStatus(9006L)).isEqualTo("IGNORED");
    }

    @Test
    void missingReferenceIsLoggedButDoesNotSettleAnyOrder() throws Exception {
        PendingCourseOrder pending = createPendingSepayCourseOrder("missing-reference");
        String body = webhookBodyWithoutReference(9007L, "in", 250000L);

        postWebhook(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(orderStatus(pending.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(eventStatus(9007L)).isEqualTo("IGNORED");
        assertThat(eventFailureReason(9007L)).containsIgnoringCase("missing");
    }

    private org.springframework.test.web.servlet.ResultActions postWebhook(String body) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return mockMvc.perform(post("/api/webhooks/sepay")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-SePay-Timestamp", timestamp)
                .header("X-SePay-Signature", signature(timestamp, body, WEBHOOK_SECRET))
                .content(body));
    }

    private String webhookBody(long id, String reference, String transferType, long amount) {
        return """
                {
                  "id": %d,
                  "code": "%s",
                  "content": "%s",
                  "transferType": "%s",
                  "transferAmount": %d,
                  "referenceCode": "BANK-%d",
                  "transactionDate": "2026-09-28 10:15:30",
                  "gateway": "Vietcombank",
                  "accountNumber": "0010000000355",
                  "subAccount": null,
                  "description": "Thanh toan %s",
                  "accumulated": 1000000
                }
                """.formatted(id, reference, reference, transferType, amount, id, reference);
    }

    private String webhookBodyWithoutReference(long id, String transferType, long amount) {
        return """
                {
                  "id": %d,
                  "code": null,
                  "content": "Thanh toan hoc phi",
                  "transferType": "%s",
                  "transferAmount": %d,
                  "referenceCode": null,
                  "transactionDate": "2026-09-28 10:15:30",
                  "gateway": "Vietcombank",
                  "accountNumber": "0010000000355",
                  "subAccount": null,
                  "description": "Thanh toan hoc phi",
                  "accumulated": 1000000
                }
                """.formatted(id, transferType, amount);
    }

    private String signature(String timestamp, String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(digest);
    }

    private PendingCourseOrder createPendingSepayCourseOrder(String suffix) throws Exception {
        User admin = saveUser("admin-" + suffix + "@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher-" + suffix + "@example.com", UserRole.TEACHER);
        User student = saveUser("student-" + suffix + "@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "SEPAY Course " + suffix, "A2", 250000L);

        MvcResult checkout = mockMvc.perform(post("/api/student/checkout/course/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentProvider").value("SEPAY"))
                .andExpect(jsonPath("$.paymentReference").isNotEmpty())
                .andReturn();
        JsonNode body = objectMapper.readTree(checkout.getResponse().getContentAsString());
        return new PendingCourseOrder(body.get("id").asLong(), body.get("paymentReference").asText(), student.getId(), courseId);
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

    private String eventStatus(long sepayTransactionId) {
        return jdbcTemplate.queryForObject(
                "select status from sepay_webhook_events where sepay_transaction_id = ?",
                String.class,
                sepayTransactionId);
    }

    private String eventFailureReason(long sepayTransactionId) {
        return jdbcTemplate.queryForObject(
                "select failure_reason from sepay_webhook_events where sepay_transaction_id = ?",
                String.class,
                sepayTransactionId);
    }

    private int eventCount(long sepayTransactionId) {
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
                """, Integer.class, userId, courseId, Instant.now(), Instant.now());
    }

    private record PendingCourseOrder(long orderId, String paymentReference, Long studentId, long courseId) {}
}
