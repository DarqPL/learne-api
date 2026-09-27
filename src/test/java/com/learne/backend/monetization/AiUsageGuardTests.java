package com.learne.backend.monetization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.monetization.entity.AiUsageEvent;
import com.learne.backend.monetization.entity.CourseEntitlement;
import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.entity.OrderItem;
import com.learne.backend.monetization.entity.Subscription;
import com.learne.backend.monetization.enums.AiUsageFeature;
import com.learne.backend.monetization.enums.CourseEntitlementSource;
import com.learne.backend.monetization.enums.OrderItemProductType;
import com.learne.backend.monetization.enums.OrderStatus;
import com.learne.backend.monetization.enums.OrderType;
import com.learne.backend.monetization.enums.PaymentProvider;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.monetization.enums.SubscriptionStatus;
import com.learne.backend.monetization.repository.AiUsageEventRepository;
import com.learne.backend.monetization.repository.CourseEntitlementRepository;
import com.learne.backend.monetization.repository.OrderRepository;
import com.learne.backend.monetization.repository.SubscriptionRepository;
import com.learne.backend.monetization.service.AiUsageGuard;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class AiUsageGuardTests {
    private static final Instant FIXED_NOW = Instant.parse("2035-02-03T12:00:00Z");

    @Autowired private AiUsageGuard aiUsageGuard;
    @Autowired private AiUsageEventRepository aiUsageEventRepository;
    @Autowired private CourseEntitlementRepository courseEntitlementRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void freeStudentLimitIsFive() {
        User student = saveUser("free@example.com", UserRole.STUDENT);

        assertThat(aiUsageGuard.snapshot(student).dailyLimit()).isEqualTo(5);
    }

    @Test
    void coursePurchaseStudentWithoutActivePlanLimitIsTen() {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("purchased@example.com", UserRole.STUDENT);
        saveCoursePurchaseEntitlement(student, teacher);

        assertThat(aiUsageGuard.snapshot(student).dailyLimit()).isEqualTo(10);
    }

    @Test
    void vipLimitIsFifty() {
        User student = saveUser("vip@example.com", UserRole.STUDENT);
        saveSubscription(student, PlanCode.VIP);

        assertThat(aiUsageGuard.snapshot(student).dailyLimit()).isEqualTo(50);
    }

    @Test
    void proLimitIsOneHundredFifty() {
        User student = saveUser("pro@example.com", UserRole.STUDENT);
        saveSubscription(student, PlanCode.PRO);

        assertThat(aiUsageGuard.snapshot(student).dailyLimit()).isEqualTo(150);
    }

    @Test
    void proPlusLimitIsOneHundredFifty() {
        User student = saveUser("pro-plus@example.com", UserRole.STUDENT);
        saveSubscription(student, PlanCode.PRO_PLUS);

        assertThat(aiUsageGuard.snapshot(student).dailyLimit()).isEqualTo(150);
    }

    @Test
    void guardThrowsTooManyRequestsAfterLimitAndDoesNotInsertAnotherUsageEvent() {
        User student = saveUser("quota@example.com", UserRole.STUDENT);
        for (int i = 0; i < 5; i++) {
            aiUsageGuard.recordAcceptedUsage(student, AiUsageFeature.AI_TUTOR);
        }

        assertThatThrownBy(() -> aiUsageGuard.recordAcceptedUsage(student, AiUsageFeature.AI_TUTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(aiUsageEventRepository.count()).isEqualTo(5);
    }

    @Test
    void lockedStudentGetsForbiddenBeforeQuotaIsConsumed() {
        User student = saveUser("locked@example.com", UserRole.STUDENT);
        student.setLocked(true);
        student = userRepository.save(student);

        User lockedStudent = student;
        assertThatThrownBy(() -> aiUsageGuard.recordAcceptedUsage(lockedStudent, AiUsageFeature.AI_TUTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(aiUsageEventRepository.count()).isZero();
    }

    @Test
    void inactiveStudentGetsForbiddenBeforeQuotaIsConsumed() {
        User student = saveUser("inactive@example.com", UserRole.STUDENT);
        student.setActive(false);
        student = userRepository.save(student);

        User inactiveStudent = student;
        assertThatThrownBy(() -> aiUsageGuard.recordAcceptedUsage(inactiveStudent, AiUsageFeature.AI_TUTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(aiUsageEventRepository.count()).isZero();
    }

    @Test
    void recordAcceptedUsageChecksFreshLockedUserStateBeforeQuotaIsConsumed() {
        User student = saveUser("fresh-lock@example.com", UserRole.STUDENT);
        User stalePrincipal = student;
        entityManager.detach(stalePrincipal);
        jdbcTemplate.update("update users set is_locked = true where id = ?", student.getId());

        assertThatThrownBy(() -> aiUsageGuard.recordAcceptedUsage(stalePrincipal, AiUsageFeature.AI_TUTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(aiUsageEventRepository.count()).isZero();
    }

    @Test
    void usedTodayCountsUtcDayStartInclusiveAndNextDayExclusive() {
        User student = saveUser("utc-boundary@example.com", UserRole.STUDENT);
        Instant todayStart = LocalDate.ofInstant(FIXED_NOW, java.time.ZoneOffset.UTC)
                .atStartOfDay()
                .toInstant(java.time.ZoneOffset.UTC);
        saveUsageEvent(student, todayStart.minusNanos(1));
        saveUsageEvent(student, todayStart);
        saveUsageEvent(student, todayStart.plusSeconds(86_400).minusNanos(1));
        saveUsageEvent(student, todayStart.plusSeconds(86_400));

        assertThat(aiUsageGuard.snapshot(student).usedToday()).isEqualTo(2);
    }

    @Test
    void activeVipWithPurchasedEntitlementUsesVipLimit() {
        User teacher = saveUser("vip-teacher@example.com", UserRole.TEACHER);
        User student = saveUser("vip-purchased@example.com", UserRole.STUDENT);
        saveSubscription(student, PlanCode.VIP);
        saveCoursePurchaseEntitlement(student, teacher);

        assertThat(aiUsageGuard.snapshot(student).dailyLimit()).isEqualTo(50);
    }

    @Test
    void purchasedEntitlementTierIgnoresExpiredFutureRevokedAndNonPurchaseEntitlements() {
        User teacher = saveUser("edge-teacher@example.com", UserRole.TEACHER);
        User student = saveUser("edge-student@example.com", UserRole.STUDENT);
        saveCourseEntitlement(student, teacher, CourseEntitlementSource.COURSE_PURCHASE,
                FIXED_NOW.minusSeconds(3600), FIXED_NOW.minusSeconds(60), null);
        saveCourseEntitlement(student, teacher, CourseEntitlementSource.COURSE_PURCHASE,
                FIXED_NOW.plusSeconds(60), null, null);
        saveCourseEntitlement(student, teacher, CourseEntitlementSource.COURSE_PURCHASE,
                FIXED_NOW.minusSeconds(60), null, FIXED_NOW.minusSeconds(30));
        saveCourseEntitlement(student, teacher, CourseEntitlementSource.TEACHER_CREATED_ACCOUNT,
                FIXED_NOW.minusSeconds(60), null, null);

        assertThat(aiUsageGuard.snapshot(student).dailyLimit()).isEqualTo(5);
    }

    @Test
    void activeProWithActiveVipUsesProLimit() {
        User student = saveUser("pro-vip@example.com", UserRole.STUDENT);
        saveSubscription(student, PlanCode.VIP);
        saveSubscription(student, PlanCode.PRO);

        assertThat(aiUsageGuard.snapshot(student).dailyLimit()).isEqualTo(150);
    }

    @Test
    void activeProPlusWithActiveVipUsesProPlusLimit() {
        User student = saveUser("pro-plus-vip@example.com", UserRole.STUDENT);
        saveSubscription(student, PlanCode.VIP);
        saveSubscription(student, PlanCode.PRO_PLUS);

        assertThat(aiUsageGuard.snapshot(student).dailyLimit()).isEqualTo(150);
    }

    @Test
    void subscriptionSummaryReturnsActualUsedTodayCountFromGuardSnapshot() throws Exception {
        User student = saveUser("summary@example.com", UserRole.STUDENT);
        saveUsageEvent(student);
        saveUsageEvent(student);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/student/subscription")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiDailyLimit").value(5))
                .andExpect(jsonPath("$.aiUsedToday").value(2));
    }

    @Test
    void subscriptionSummaryUsesGuardClockForActivePlanSelection() throws Exception {
        User student = saveUser("summary-clock@example.com", UserRole.STUDENT);
        saveSubscription(student, PlanCode.VIP, FIXED_NOW.minusSeconds(4000L * 86_400), FIXED_NOW.minusSeconds(60));
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/student/subscription")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").doesNotExist())
                .andExpect(jsonPath("$.aiDailyLimit").value(5));
    }

    private void saveUsageEvent(User student) {
        saveUsageEvent(student, FIXED_NOW);
    }

    private void saveUsageEvent(User student, Instant usedAt) {
        AiUsageEvent event = new AiUsageEvent();
        event.setUser(student);
        event.setFeature(AiUsageFeature.AI_TUTOR);
        event.setUsedAt(usedAt);
        aiUsageEventRepository.save(event);
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, java.time.ZoneOffset.UTC);
        }
    }

    private void saveCoursePurchaseEntitlement(User student, User teacher) {
        saveCourseEntitlement(student, teacher, CourseEntitlementSource.COURSE_PURCHASE,
                FIXED_NOW.minusSeconds(60), null, null);
    }

    private void saveCourseEntitlement(User student, User teacher, CourseEntitlementSource source,
                                       Instant startsAt, Instant endsAt, Instant revokedAt) {
        Course course = new Course();
        course.setTeacher(teacher);
        course.setName("Paid Course " + System.nanoTime());
        course.setDescription("Paid course description");
        course.setType(CourseType.GRAMMAR);
        course.setLevel("A1");
        course.setOrderIndex(1);
        course.setStatus(ContentStatus.APPROVED);
        course.setPublishedAt(FIXED_NOW);
        course = courseRepository.save(course);

        CourseEntitlement entitlement = new CourseEntitlement();
        entitlement.setUser(student);
        entitlement.setCourse(course);
        entitlement.setSource(source);
        entitlement.setStartsAt(startsAt);
        entitlement.setEndsAt(endsAt);
        entitlement.setRevokedAt(revokedAt);
        courseEntitlementRepository.save(entitlement);
    }

    private void saveSubscription(User student, PlanCode planCode) {
        saveSubscription(student, planCode, FIXED_NOW.minusSeconds(60), FIXED_NOW.plusSeconds(3600));
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

    private Order savePaidPlanOrder(User student, PlanCode planCode) {
        Order order = new Order();
        order.setUser(student);
        order.setOrderType(OrderType.PLAN_SUBSCRIPTION);
        order.setStatus(OrderStatus.PAID);
        order.setOriginalAmountVnd(500000L);
        order.setDiscountAmountVnd(0L);
        order.setFinalAmountVnd(500000L);
        order.setPaymentProvider(PaymentProvider.INTERNAL);
        order.setPaidAt(FIXED_NOW);
        order.setExpiresAt(FIXED_NOW.plusSeconds(900));

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
                                  "password": "Password123!"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }
}
