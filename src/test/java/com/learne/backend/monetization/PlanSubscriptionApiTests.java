package com.learne.backend.monetization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.AccountOrigin;
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
@SpringBootTest(properties = {
        "app.monetization.plans.vip-price-vnd=200000",
        "app.monetization.plans.pro-price-vnd=500000",
        "app.monetization.plans.pro-plus-price-vnd=350000"
})
class PlanSubscriptionApiTests {
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void activeSubscriptionCanBePersistedAndFound() {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Order order = savePaidPlanOrder(student, PlanCode.PRO);

        Subscription subscription = new Subscription();
        subscription.setUser(student);
        subscription.setPlanCode(PlanCode.PRO);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartsAt(Instant.now().minusSeconds(60));
        subscription.setEndsAt(Instant.now().plusSeconds(3600));
        subscription.setOrder(order);

        subscriptionRepository.save(subscription);

        assertThat(subscriptionRepository.existsActivePlan(student, PlanCode.PRO, Instant.now())).isTrue();
    }

    @Test
    void futureStartingActiveSubscriptionIsNotCurrent() {
        User student = saveUser("future-student@example.com", UserRole.STUDENT);
        Order order = savePaidPlanOrder(student, PlanCode.PRO);
        Instant now = Instant.now();

        Subscription subscription = new Subscription();
        subscription.setUser(student);
        subscription.setPlanCode(PlanCode.PRO);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartsAt(now.plusSeconds(3600));
        subscription.setEndsAt(now.plusSeconds(7200));
        subscription.setOrder(order);

        subscriptionRepository.save(subscription);

        assertThat(subscriptionRepository.existsActivePlan(student, PlanCode.PRO, now)).isFalse();
        assertThat(subscriptionRepository.findFirstByUserAndStatusAndEndsAtAfterOrderByEndsAtDesc(
                student,
                SubscriptionStatus.ACTIVE,
                now)).isEmpty();
        assertThat(subscriptionRepository.findByUserAndStatusAndEndsAtAfter(
                student,
                SubscriptionStatus.ACTIVE,
                now)).isEmpty();
    }

    @Test
    void studentCanCreateProOrderAndAdminMarkPaidCreatesSubscription() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());

        MvcResult checkoutResult = mockMvc.perform(post("/api/student/checkout/plans/PRO")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderType").value("PLAN_SUBSCRIPTION"))
                .andExpect(jsonPath("$.finalAmountVnd").value(500000))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].productType").value("PLAN"))
                .andExpect(jsonPath("$.items[0].planCode").value("PRO"))
                .andReturn();
        long orderId = objectMapper.readTree(checkoutResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/admin/orders/%d/mark-paid".formatted(orderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(get("/api/student/subscription")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("PRO"))
                .andExpect(jsonPath("$.aiDailyLimit").value(150))
                .andExpect(jsonPath("$.aiUsedToday").value(0));
        assertThat(subscriptionRepository.count()).isEqualTo(1);

        mockMvc.perform(post("/api/admin/orders/%d/mark-paid".formatted(orderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        assertThat(subscriptionRepository.count()).isEqualTo(1);
    }

    @Test
    void expiredSubscriptionAllowsFreshPlanCheckout() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());

        MvcResult firstCheckoutResult = mockMvc.perform(post("/api/student/checkout/plans/PRO")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isCreated())
                .andReturn();
        long firstOrderId = objectMapper.readTree(firstCheckoutResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/admin/orders/%d/mark-paid".formatted(firstOrderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        String originalDedupeKey = jdbcTemplate.queryForObject(
                "select dedupe_key from orders where id = ?",
                String.class,
                firstOrderId);
        assertThat(originalDedupeKey).isNotBlank();

        Instant expiredAt = Instant.now().minusSeconds(60);
        jdbcTemplate.update("update subscriptions set starts_at = ?, ends_at = ? where user_id = ?",
                expiredAt.minusSeconds(3600),
                expiredAt,
                student.getId());

        MvcResult secondCheckoutResult = mockMvc.perform(post("/api/student/checkout/plans/PRO")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isCreated())
                .andReturn();
        long secondOrderId = objectMapper.readTree(secondCheckoutResult.getResponse().getContentAsString()).get("id").asLong();

        assertThat(secondOrderId).isNotEqualTo(firstOrderId);
    }

    @Test
    void duplicatePlanCheckoutWhilePendingReturnsBadRequest() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(post("/api/student/checkout/plans/PRO")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.items[0].planCode").value("PRO"));

        mockMvc.perform(post("/api/student/checkout/plans/PRO")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pending order already exists."));
    }

    @Test
    void subscriptionSummaryIncludesPeriod() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());

        MvcResult checkoutResult = mockMvc.perform(post("/api/student/checkout/plans/PRO")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isCreated())
                .andReturn();
        long orderId = objectMapper.readTree(checkoutResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/admin/orders/%d/mark-paid".formatted(orderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/subscription")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startsAt").exists())
                .andExpect(jsonPath("$.endsAt").exists());
    }

    @Test
    void subscriptionSummaryUsesStrongestActivePlanNotLatestEndingPlan() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken(student.getEmail());
        Instant now = Instant.now();

        saveSubscription(student, PlanCode.PRO, now.minusSeconds(60), now.plusSeconds(3600));
        saveSubscription(student, PlanCode.VIP, now.minusSeconds(60), now.plusSeconds(7200));

        mockMvc.perform(get("/api/student/subscription")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("PRO"))
                .andExpect(jsonPath("$.aiDailyLimit").value(150));
    }

    @Test
    void selfRegisteredStudentCannotCheckoutProPlus() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(post("/api/student/checkout/plans/PRO_PLUS")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teacherCreatedStudentCanCheckoutProPlus() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = new User();
        student.setEmail("student@example.com");
        student.setFullName("Teacher Created Student");
        student.setPasswordHash(passwordEncoder.encode("Password123!"));
        student.setRole(UserRole.STUDENT);
        student.setActive(true);
        student.setAccountOrigin(AccountOrigin.TEACHER_CREATED);
        student.setOwnerTeacher(teacher);
        student = userRepository.save(student);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(post("/api/student/checkout/plans/PRO_PLUS")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderType").value("PLAN_SUBSCRIPTION"))
                .andExpect(jsonPath("$.finalAmountVnd").value(350000))
                .andExpect(jsonPath("$.items[0].productType").value("PLAN"))
                .andExpect(jsonPath("$.items[0].planCode").value("PRO_PLUS"));
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

    private void saveSubscription(User student, PlanCode planCode, Instant startsAt, Instant endsAt) {
        Subscription subscription = new Subscription();
        subscription.setUser(student);
        subscription.setPlanCode(planCode);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartsAt(startsAt);
        subscription.setEndsAt(endsAt);
        subscription.setOrder(savePaidPlanOrder(student, planCode));
        subscriptionRepository.save(subscription);
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
