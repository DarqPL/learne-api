package com.learne.backend.monetization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.dto.CheckoutPreviewRequest;
import com.learne.backend.monetization.dto.CheckoutPreviewResponse;
import com.learne.backend.monetization.entity.Coupon;
import com.learne.backend.monetization.entity.CouponCourseScope;
import com.learne.backend.monetization.entity.CouponTeacherScope;
import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.enums.CouponDiscountType;
import com.learne.backend.monetization.enums.CouponProductScope;
import com.learne.backend.monetization.enums.OrderItemProductType;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.monetization.repository.CouponRedemptionRepository;
import com.learne.backend.monetization.repository.CouponRepository;
import com.learne.backend.monetization.repository.OrderRepository;
import com.learne.backend.monetization.repository.SubscriptionRepository;
import com.learne.backend.monetization.service.CheckoutService;
import com.learne.backend.monetization.service.CourseAccessService;
import com.learne.backend.monetization.service.CouponPricingResult;
import com.learne.backend.monetization.service.CouponPricingService;
import com.learne.backend.monetization.service.CouponProduct;
import com.learne.backend.monetization.service.PlanCatalogService;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CoursePriceStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CouponPricingServiceTests {
    @Mock private CouponRepository couponRepository;
    @Mock private CouponRedemptionRepository couponRedemptionRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private PlanCatalogService planCatalogService;
    @Mock private OrderRepository orderRepository;
    @Mock private CourseAccessService courseAccessService;
    @Mock private SubscriptionRepository subscriptionRepository;

    private long nextCouponId = 100L;

    @Test
    void percentCouponAppliesMaxDiscountAndMinimumFinalAmount() {
        User student = user(1L);
        Coupon coupon = coupon("WELCOME20", CouponDiscountType.PERCENT, 50L);
        coupon.setMaxDiscountAmountVnd(80000L);
        coupon.setMinimumFinalAmountVnd(250000L);
        coupon.getProductScopes().add(CouponProductScope.COURSE_PURCHASE);
        stubCoupon(coupon);

        CouponPricingResult result = service().price(student, courseProduct(400000L), " welcome20 ");

        assertThat(result.originalAmountVnd()).isEqualTo(400000L);
        assertThat(result.discountAmountVnd()).isEqualTo(80000L);
        assertThat(result.finalAmountVnd()).isEqualTo(320000L);
        assertThat(result.couponId()).isEqualTo(100L);
        assertThat(result.couponCode()).isEqualTo("WELCOME20");
    }

    @Test
    void fixedAmountCouponCannotReduceBelowMinimumFinalAmount() {
        User student = user(1L);
        Coupon coupon = coupon("SAVEBIG", CouponDiscountType.FIXED_AMOUNT, 500000L);
        coupon.setMinimumFinalAmountVnd(10000L);
        coupon.getProductScopes().add(CouponProductScope.COURSE_PURCHASE);
        stubCoupon(coupon);

        CouponPricingResult result = service().price(student, courseProduct(120000L), "SAVEBIG");

        assertThat(result.discountAmountVnd()).isEqualTo(110000L);
        assertThat(result.finalAmountVnd()).isEqualTo(10000L);
    }

    @Test
    void previewWithCouponUsesNonLockingCouponLookup() {
        User student = user(1L);
        Coupon coupon = coupon("PLAN10", CouponDiscountType.PERCENT, 10L);
        coupon.getProductScopes().add(CouponProductScope.VIP);
        when(planCatalogService.get(PlanCode.VIP))
                .thenReturn(new PlanCatalogService.PlanCatalogItem(PlanCode.VIP, "VIP Plan", 200000L));
        when(couponRepository.findByCodeNormalized(coupon.getCodeNormalized())).thenReturn(Optional.of(coupon));

        CheckoutPreviewResponse response = service().preview(
                student,
                new CheckoutPreviewRequest("PLAN", null, PlanCode.VIP, " plan10 "));

        assertThat(response.discountAmountVnd()).isEqualTo(20000L);
        assertThat(response.finalAmountVnd()).isEqualTo(180000L);
        verify(couponRepository, never()).findByIdForUpdate(coupon.getId());
    }

    @Test
    void courseCheckoutLocksDedupeBeforeCouponForDiscountedOrder() {
        User student = user(1L);
        User teacher = user(2L);
        Course course = paidCourse(10L, teacher, 250000L);
        Coupon coupon = coupon("WELCOME20", CouponDiscountType.PERCENT, 20L);
        coupon.getProductScopes().add(CouponProductScope.COURSE_PURCHASE);
        List<String> lockEvents = new ArrayList<>();
        when(courseRepository.findStudentDetailByIdAndStatus(10L, ContentStatus.APPROVED)).thenReturn(Optional.of(course));
        when(courseAccessService.canEnroll(student, course)).thenReturn(false);
        when(couponRepository.findByCodeNormalized(coupon.getCodeNormalized())).thenReturn(Optional.of(coupon));
        when(couponRepository.findByIdForUpdate(coupon.getId())).thenAnswer(invocation -> {
            lockEvents.add("coupon-lock");
            return Optional.of(coupon);
        });
        when(orderRepository.findByDedupeKeyForUpdate("COURSE:1:10")).thenAnswer(invocation -> {
            lockEvents.add("dedupe-lock");
            return List.of();
        });
        when(orderRepository.saveAndFlush(ArgumentMatchers.any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        checkoutService().createCourseOrder(student, 10L, "WELCOME20");

        assertThat(lockEvents).containsExactly("dedupe-lock", "coupon-lock");
    }

    @Test
    void couponRejectsInactiveExpiredFutureAndBelowMinimumOrder() {
        User student = user(1L);
        Coupon inactive = coupon("INACTIVE", CouponDiscountType.PERCENT, 10L);
        inactive.setActive(false);
        inactive.getProductScopes().add(CouponProductScope.COURSE_PURCHASE);
        Coupon expired = coupon("EXPIRED", CouponDiscountType.PERCENT, 10L);
        expired.setValidUntil(Instant.now().minusSeconds(60));
        expired.getProductScopes().add(CouponProductScope.COURSE_PURCHASE);
        Coupon future = coupon("FUTURE", CouponDiscountType.PERCENT, 10L);
        future.setValidFrom(Instant.now().plusSeconds(60));
        future.getProductScopes().add(CouponProductScope.COURSE_PURCHASE);
        Coupon belowMinimum = coupon("MINIMUM", CouponDiscountType.PERCENT, 10L);
        belowMinimum.setMinOrderAmountVnd(300000L);
        belowMinimum.getProductScopes().add(CouponProductScope.COURSE_PURCHASE);

        stubCoupon(inactive);
        stubCoupon(expired);
        stubCoupon(future);
        stubCoupon(belowMinimum);

        assertInvalid(student, "INACTIVE", "Coupon is not active.");
        assertInvalid(student, "EXPIRED", "Coupon is not valid.");
        assertInvalid(student, "FUTURE", "Coupon is not valid.");
        assertInvalid(student, "MINIMUM", "Order does not meet coupon minimum amount.");
    }

    @Test
    void couponRejectsWrongProductCourseTeacherAndUsageLimits() {
        User student = user(1L);
        Coupon wrongProduct = coupon("PLANONLY", CouponDiscountType.PERCENT, 10L);
        wrongProduct.getProductScopes().add(CouponProductScope.VIP);
        Coupon wrongCourse = coupon("COURSE", CouponDiscountType.PERCENT, 10L);
        wrongCourse.getProductScopes().add(CouponProductScope.COURSE_PURCHASE);
        wrongCourse.getCourseScopes().add(new CouponCourseScope(wrongCourse, 99L));
        Coupon wrongTeacher = coupon("TEACHER", CouponDiscountType.PERCENT, 10L);
        wrongTeacher.getProductScopes().add(CouponProductScope.COURSE_PURCHASE);
        wrongTeacher.getTeacherScopes().add(new CouponTeacherScope(wrongTeacher, 88L));
        Coupon totalLimit = coupon("TOTAL", CouponDiscountType.PERCENT, 10L);
        totalLimit.setUsageLimitTotal(2);
        totalLimit.getProductScopes().add(CouponProductScope.COURSE_PURCHASE);
        Coupon userLimit = coupon("USER", CouponDiscountType.PERCENT, 10L);
        userLimit.setUsageLimitPerUser(1);
        userLimit.getProductScopes().add(CouponProductScope.COURSE_PURCHASE);
        Coupon scopedPlan = coupon("SCOPEDPLAN", CouponDiscountType.PERCENT, 10L);
        scopedPlan.getProductScopes().add(CouponProductScope.VIP);
        scopedPlan.getCourseScopes().add(new CouponCourseScope(scopedPlan, 10L));

        stubCoupon(wrongProduct);
        stubCoupon(wrongCourse);
        stubCoupon(wrongTeacher);
        stubCoupon(totalLimit);
        stubCoupon(userLimit);
        stubCoupon(scopedPlan);
        when(couponRedemptionRepository.countByCouponId(totalLimit.getId())).thenReturn(2L);
        when(couponRedemptionRepository.countByCouponIdAndUser(userLimit.getId(), student)).thenReturn(1L);

        assertInvalid(student, "PLANONLY", "Coupon is not eligible for this product.");
        assertInvalid(student, "COURSE", "Coupon is not eligible for this course.");
        assertInvalid(student, "TEACHER", "Coupon is not eligible for this teacher.");
        assertInvalid(student, "TOTAL", "Coupon usage limit has been reached.");
        assertInvalid(student, "USER", "Coupon usage limit has been reached.");

        assertThatThrownBy(() -> service().price(student, planProduct(), "SCOPEDPLAN"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Coupon is not eligible for this product.");
    }

    private void assertInvalid(User student, String couponCode, String message) {
        assertThatThrownBy(() -> service().price(student, courseProduct(200000L), couponCode))
                .isInstanceOf(ApiException.class)
                .hasMessage(message);
    }

    private CouponPricingService service() {
        return new CouponPricingService(couponRepository, couponRedemptionRepository, courseRepository, planCatalogService);
    }

    private CheckoutService checkoutService() {
        return new CheckoutService(
                courseRepository,
                orderRepository,
                courseAccessService,
                subscriptionRepository,
                planCatalogService,
                service());
    }

    private void stubCoupon(Coupon coupon) {
        when(couponRepository.findByCodeNormalized(coupon.getCodeNormalized())).thenReturn(Optional.of(coupon));
        when(couponRepository.findByIdForUpdate(coupon.getId())).thenReturn(Optional.of(coupon));
    }

    private Coupon coupon(String code, CouponDiscountType discountType, Long discountValue) {
        Coupon coupon = new Coupon();
        ReflectionTestUtils.setField(coupon, "id", nextCouponId++);
        coupon.setCode(code);
        coupon.setCodeNormalized(code);
        coupon.setDiscountType(discountType);
        coupon.setDiscountValue(discountValue);
        coupon.setMinOrderAmountVnd(0L);
        coupon.setMinimumFinalAmountVnd(0L);
        coupon.setActive(true);
        coupon.setValidFrom(Instant.now().minusSeconds(3600));
        coupon.setValidUntil(Instant.now().plusSeconds(3600));
        return coupon;
    }

    private User user(Long id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Course paidCourse(Long id, User teacher, long amountVnd) {
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", id);
        course.setTeacher(teacher);
        course.setName("Course");
        course.setStatus(ContentStatus.APPROVED);
        course.setPricingType(CoursePricingType.PAID);
        course.setPriceStatus(CoursePriceStatus.APPROVED);
        course.setPriceAmountVnd(amountVnd);
        return course;
    }

    private CouponProduct courseProduct(long amountVnd) {
        return new CouponProduct(OrderItemProductType.COURSE, 10L, null, 20L, "Course", amountVnd);
    }

    private CouponProduct planProduct() {
        return new CouponProduct(OrderItemProductType.PLAN, null, PlanCode.VIP, null, "VIP", 200000L);
    }
}
