package com.learne.backend.monetization;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.test.context.NestedTestConfiguration;
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
                .andExpect(jsonPath("$.paymentProvider").value("INTERNAL"))
                .andExpect(jsonPath("$.paymentInstructions").value(nullValue()))
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
        jdbcTemplate.update("update orders set expires_at = ?, dedupe_key = null where id = ?", Instant.now().minusSeconds(1), firstOrderId);
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

    @Test
    void expiredPendingOrderClearsDedupeKeyAndAllowsFreshCheckout() throws Exception {
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

        org.assertj.core.api.Assertions.assertThat(
                jdbcTemplate.queryForObject("select dedupe_key from orders where id = ?", String.class, orderId))
                .isNull();
        checkoutCourse(studentToken, courseId);
    }

    @Test
    void naturallyExpiredPendingOrderAllowsFreshCheckoutWithoutAdminAction() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        long orderId = checkoutCourse(studentToken, courseId);
        jdbcTemplate.update("update orders set expires_at = ? where id = ?", Instant.now().minusSeconds(1), orderId);

        long freshOrderId = checkoutCourse(studentToken, courseId);

        org.assertj.core.api.Assertions.assertThat(freshOrderId).isNotEqualTo(orderId);
        org.assertj.core.api.Assertions.assertThat(
                jdbcTemplate.queryForObject("select status from orders where id = ?", String.class, orderId))
                .isEqualTo("EXPIRED");
        org.assertj.core.api.Assertions.assertThat(
                jdbcTemplate.queryForObject("select dedupe_key from orders where id = ?", String.class, orderId))
                .isNull();
    }

    @Test
    void previewCourseCheckoutReturnsDiscountBreakdown() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        long couponId = createCoupon(adminToken, courseCouponRequest("WELCOME20"));

        mockMvc.perform(post("/api/student/checkout/preview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productType": "COURSE",
                                  "courseId": %d,
                                  "planCode": null,
                                  "couponCode": "WELCOME20"
                                }
                                """.formatted(courseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productType").value("COURSE"))
                .andExpect(jsonPath("$.courseId").value(courseId))
                .andExpect(jsonPath("$.couponId").value(couponId))
                .andExpect(jsonPath("$.couponCode").value("WELCOME20"))
                .andExpect(jsonPath("$.originalAmountVnd").value(250000))
                .andExpect(jsonPath("$.discountAmountVnd").value(50000))
                .andExpect(jsonPath("$.finalAmountVnd").value(200000));
    }

    @Test
    void courseCheckoutWithValidCouponStoresDiscountAndCouponId() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        long couponId = createCoupon(adminToken, courseCouponRequest("WELCOME20"));

        MvcResult checkoutResult = mockMvc.perform(post("/api/student/checkout/course/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "couponCode": "WELCOME20" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.couponId").value(couponId))
                .andExpect(jsonPath("$.originalAmountVnd").value(250000))
                .andExpect(jsonPath("$.discountAmountVnd").value(50000))
                .andExpect(jsonPath("$.finalAmountVnd").value(200000))
                .andReturn();
        long orderId = objectMapper.readTree(checkoutResult.getResponse().getContentAsString()).get("id").asLong();

        Long storedCouponId = jdbcTemplate.queryForObject("select coupon_id from orders where id = ?", Long.class, orderId);
        Long storedDiscount = jdbcTemplate.queryForObject("select discount_amount_vnd from orders where id = ?", Long.class, orderId);
        org.assertj.core.api.Assertions.assertThat(storedCouponId).isEqualTo(couponId);
        org.assertj.core.api.Assertions.assertThat(storedDiscount).isEqualTo(50000L);
    }

    @Test
    void markPaidCreatesCouponRedemptionForDiscountedCourseOrder() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        long couponId = createCoupon(adminToken, courseCouponRequest("WELCOME20"));
        long orderId = checkoutCourse(studentToken, courseId, "WELCOME20");

        markPaid(adminToken, orderId);

        Integer redemptionCount = jdbcTemplate.queryForObject("""
                select count(*)
                from coupon_redemptions
                where coupon_id = ?
                  and user_id = ?
                  and order_id = ?
                  and discount_amount_vnd = 50000
                  and redeemed_at is not null
                """, Integer.class, couponId, student.getId(), orderId);
        org.assertj.core.api.Assertions.assertThat(redemptionCount).isEqualTo(1);
    }

    @Test
    void repeatedMarkPaidDoesNotCreateDuplicateCouponRedemption() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        long couponId = createCoupon(adminToken, courseCouponRequest("WELCOME20"));
        long orderId = checkoutCourse(studentToken, courseId, "WELCOME20");

        markPaid(adminToken, orderId);
        markPaid(adminToken, orderId);

        Integer redemptionCount = jdbcTemplate.queryForObject(
                "select count(*) from coupon_redemptions where coupon_id = ? and order_id = ?",
                Integer.class,
                couponId,
                orderId);
        org.assertj.core.api.Assertions.assertThat(redemptionCount).isEqualTo(1);
    }

    @Test
    void paidRedemptionsEnforceTotalLimitForNewCourseCheckout() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User firstStudent = saveUser("student1@example.com", UserRole.STUDENT);
        User secondStudent = saveUser("student2@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String firstStudentToken = loginAndGetAccessToken(firstStudent.getEmail());
        String secondStudentToken = loginAndGetAccessToken(secondStudent.getEmail());
        long firstCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        long secondCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English B1 Premium", "B1", 250000L);
        createCoupon(adminToken, courseCouponRequest("ONCE").replace("\"usageLimitTotal\": 100", "\"usageLimitTotal\": 1"));
        long orderId = checkoutCourse(firstStudentToken, firstCourseId, "ONCE");
        markPaid(adminToken, orderId);

        mockMvc.perform(post("/api/student/checkout/course/%d".formatted(secondCourseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secondStudentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "couponCode": "ONCE" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Coupon usage limit has been reached."));
    }

    @Test
    void pendingDiscountedOrdersDoNotConsumeTotalLimitForNewCourseCheckout() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User firstStudent = saveUser("student1@example.com", UserRole.STUDENT);
        User secondStudent = saveUser("student2@example.com", UserRole.STUDENT);
        User thirdStudent = saveUser("student3@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String firstStudentToken = loginAndGetAccessToken(firstStudent.getEmail());
        String secondStudentToken = loginAndGetAccessToken(secondStudent.getEmail());
        String thirdStudentToken = loginAndGetAccessToken(thirdStudent.getEmail());
        long firstCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        long secondCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English B1 Premium", "B1", 250000L);
        long thirdCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English B2 Premium", "B2", 250000L);
        createCoupon(adminToken, courseCouponRequest("PENDINGOK").replace("\"usageLimitTotal\": 100", "\"usageLimitTotal\": 1"));
        long firstOrderId = checkoutCourse(firstStudentToken, firstCourseId, "PENDINGOK");

        checkoutCourse(secondStudentToken, secondCourseId, "PENDINGOK");
        markPaid(adminToken, firstOrderId);

        mockMvc.perform(post("/api/student/checkout/course/%d".formatted(thirdCourseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + thirdStudentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "couponCode": "PENDINGOK" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Coupon usage limit has been reached."));
    }

    @Test
    void secondAlreadyPendingDiscountedCourseOrderCannotBePaidAfterTotalLimitConsumed() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User firstStudent = saveUser("student1@example.com", UserRole.STUDENT);
        User secondStudent = saveUser("student2@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String firstStudentToken = loginAndGetAccessToken(firstStudent.getEmail());
        String secondStudentToken = loginAndGetAccessToken(secondStudent.getEmail());
        long firstCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        long secondCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English B1 Premium", "B1", 250000L);
        long couponId = createCoupon(adminToken,
                courseCouponRequest("PENDINGONCE").replace("\"usageLimitTotal\": 100", "\"usageLimitTotal\": 1"));
        long firstOrderId = checkoutCourse(firstStudentToken, firstCourseId, "PENDINGONCE");
        long secondOrderId = checkoutCourse(secondStudentToken, secondCourseId, "PENDINGONCE");

        markPaid(adminToken, firstOrderId);
        mockMvc.perform(post("/api/admin/orders/%d/mark-paid".formatted(secondOrderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Coupon usage limit has been reached."));

        Integer redemptionCount = jdbcTemplate.queryForObject(
                "select count(*) from coupon_redemptions where coupon_id = ?",
                Integer.class,
                couponId);
        org.assertj.core.api.Assertions.assertThat(redemptionCount).isEqualTo(1);
    }

    @Test
    void paidRedemptionsEnforcePerUserLimitForNewCourseCheckout() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long firstCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        long secondCourseId = createApprovedPaidCourse(teacherToken, adminToken, "English B1 Premium", "B1", 250000L);
        createCoupon(adminToken, courseCouponRequest("USERONCE"));
        long orderId = checkoutCourse(studentToken, firstCourseId, "USERONCE");
        markPaid(adminToken, orderId);

        mockMvc.perform(post("/api/student/checkout/course/%d".formatted(secondCourseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "couponCode": "USERONCE" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Coupon usage limit has been reached."));
    }

    @Test
    void invalidCouponReturnsCouponErrorEvenWhenNoCouponPendingOrderExists() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        checkoutCourse(studentToken, courseId);

        mockMvc.perform(post("/api/student/checkout/course/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "couponCode": "MISSING" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Coupon not found."));
    }

    @Test
    void validCouponCheckoutRejectedWhenPendingOrderExistsForSameCourseWithoutCoupon() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        createCoupon(adminToken, courseCouponRequest("WELCOME20"));
        checkoutCourse(studentToken, courseId);

        mockMvc.perform(post("/api/student/checkout/course/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "couponCode": "WELCOME20" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pending order already exists."));

        Integer orderCount = jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);
        org.assertj.core.api.Assertions.assertThat(orderCount).isEqualTo(1);
    }

    @Test
    void validCouponCheckoutRejectedWhenPendingOrderExistsForSameCourseWithDifferentCoupon() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);
        createCoupon(adminToken, courseCouponRequest("WELCOME20"));
        createCoupon(adminToken, courseCouponRequest("SAVE20"));
        checkoutCourse(studentToken, courseId, "WELCOME20");

        mockMvc.perform(post("/api/student/checkout/course/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "couponCode": "SAVE20" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pending order already exists."));

        Integer orderCount = jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);
        org.assertj.core.api.Assertions.assertThat(orderCount).isEqualTo(1);
    }

    @Test
    void checkoutRejectsInvalidCouponWithoutCreatingOrder() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 250000L);

        mockMvc.perform(post("/api/student/checkout/course/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "couponCode": "MISSING" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Coupon not found."));

        Integer orderCount = jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);
        org.assertj.core.api.Assertions.assertThat(orderCount).isZero();
    }

    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @ActiveProfiles("test")
    @AutoConfigureMockMvc
    @SpringBootTest(properties = {
            "app.payments.sepay.enabled=true",
            "app.payments.sepay.bank-code=Vietcombank",
            "app.payments.sepay.bank-name=Vietcombank",
            "app.payments.sepay.account-number=0010000000355",
            "app.payments.sepay.account-name=CONG TY LEARNE"
    })
    class SepayEnabledCheckoutTests {
        @Test
        void courseCheckoutIncludesSepayPaymentInstructions() throws Exception {
            User admin = saveUser("sepay-admin@example.com", UserRole.ADMIN);
            User teacher = saveUser("sepay-teacher@example.com", UserRole.TEACHER);
            User student = saveUser("sepay-student@example.com", UserRole.STUDENT);
            String adminToken = loginAndGetAccessToken(admin.getEmail());
            String teacherToken = loginAndGetAccessToken(teacher.getEmail());
            String studentToken = loginAndGetAccessToken(student.getEmail());
            long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 SEPAY", "A2", 250000L);

            mockMvc.perform(post("/api/student/checkout/course/%d".formatted(courseId))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.paymentProvider").value("SEPAY"))
                    .andExpect(jsonPath("$.paymentReference").isNotEmpty())
                    .andExpect(jsonPath("$.paymentInstructions.qrUrl").value(containsString("https://vietqr.app/img?")))
                    .andExpect(jsonPath("$.paymentInstructions.amountVnd").value(250000));
        }
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

    private long checkoutCourse(String studentToken, long courseId, String couponCode) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/student/checkout/course/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "couponCode": "%s" }
                                """.formatted(couponCode)))
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

    private long createCoupon(String adminToken, String requestBody) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/coupons")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String courseCouponRequest(String code) {
        Instant now = Instant.now();
        return """
                {
                  "code": "%s",
                  "discountType": "PERCENT",
                  "discountValue": 20,
                  "minOrderAmountVnd": 100000,
                  "maxDiscountAmountVnd": 50000,
                  "minimumFinalAmountVnd": 10000,
                  "usageLimitTotal": 100,
                  "usageLimitPerUser": 1,
                  "validFrom": "%s",
                  "validUntil": "%s",
                  "productScopes": ["COURSE_PURCHASE"],
                  "courseIds": [],
                  "teacherIds": []
                }
                """.formatted(code, now.minusSeconds(86_400), now.plusSeconds(86_400));
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
