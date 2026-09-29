package com.learne.backend.monetization.service;

import com.learne.backend.domain.auth.AccountOrigin;
import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.dto.CheckoutPreviewRequest;
import com.learne.backend.monetization.dto.CheckoutPreviewResponse;
import com.learne.backend.monetization.entity.Coupon;
import com.learne.backend.monetization.entity.CouponCourseScope;
import com.learne.backend.monetization.entity.CouponTeacherScope;
import com.learne.backend.monetization.enums.CouponDiscountType;
import com.learne.backend.monetization.enums.CouponProductScope;
import com.learne.backend.monetization.enums.OrderItemProductType;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.monetization.repository.CouponRedemptionRepository;
import com.learne.backend.monetization.repository.CouponRepository;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CoursePriceStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponPricingService {
    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository couponRedemptionRepository;
    private final CourseRepository courseRepository;
    private final PlanCatalogService planCatalogService;

    public CouponPricingService(CouponRepository couponRepository,
                                CouponRedemptionRepository couponRedemptionRepository,
                                CourseRepository courseRepository,
                                PlanCatalogService planCatalogService) {
        this.couponRepository = couponRepository;
        this.couponRedemptionRepository = couponRedemptionRepository;
        this.courseRepository = courseRepository;
        this.planCatalogService = planCatalogService;
    }

    @Transactional(readOnly = true)
    public CheckoutPreviewResponse preview(User user, CheckoutPreviewRequest request) {
        if (request == null || request.productType() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "productType is required.");
        }
        CouponProduct product = switch (request.productType().trim().toUpperCase(Locale.ROOT)) {
            case "COURSE" -> courseProduct(request.courseId());
            case "PLAN" -> planProduct(user, request.planCode());
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "productType is invalid.");
        };
        CouponPricingResult pricing = price(user, product, request.couponCode(), false);
        return new CheckoutPreviewResponse(
                product.productType().name(),
                product.courseId(),
                product.planCode(),
                product.itemName(),
                pricing.couponId(),
                pricing.couponCode(),
                pricing.originalAmountVnd(),
                pricing.discountAmountVnd(),
                pricing.finalAmountVnd());
    }

    @Transactional
    public CouponPricingResult price(User user, CouponProduct product, String couponCode) {
        return price(user, product, couponCode, true);
    }

    @Transactional(readOnly = true)
    public CouponPricingResult priceWithoutLock(User user, CouponProduct product, String couponCode) {
        return price(user, product, couponCode, false);
    }

    private CouponPricingResult price(User user, CouponProduct product, String couponCode, boolean lockCoupon) {
        if (product == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Product is required.");
        }
        String normalizedCode = normalize(couponCode);
        if (normalizedCode == null) {
            return new CouponPricingResult(null, null, product.originalAmountVnd(), 0L, product.originalAmountVnd());
        }
        Coupon coupon = couponRepository.findByCodeNormalized(normalizedCode)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Coupon not found."));
        if (lockCoupon) {
            coupon = couponRepository.findByIdForUpdate(coupon.getId())
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Coupon not found."));
        }
        validate(coupon, user, product);

        long rawDiscount = coupon.getDiscountType() == CouponDiscountType.PERCENT
                ? product.originalAmountVnd() * coupon.getDiscountValue() / 100
                : coupon.getDiscountValue();
        long discountAfterMax = coupon.getMaxDiscountAmountVnd() == null
                ? rawDiscount
                : Math.min(rawDiscount, coupon.getMaxDiscountAmountVnd());
        long discountAfterMinimumFinal = Math.min(
                discountAfterMax,
                product.originalAmountVnd() - coupon.getMinimumFinalAmountVnd());
        long discount = Math.max(0L, discountAfterMinimumFinal);
        return new CouponPricingResult(
                coupon.getId(),
                coupon.getCode(),
                product.originalAmountVnd(),
                discount,
                product.originalAmountVnd() - discount);
    }

    public CouponProduct courseProduct(Long courseId) {
        if (courseId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "courseId is required.");
        }
        Course course = courseRepository.findStudentDetailByIdAndStatus(courseId, ContentStatus.APPROVED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
        Long priceAmountVnd = course.getPriceAmountVnd();
        if (course.getPricingType() != CoursePricingType.PAID
                || course.getPriceStatus() != CoursePriceStatus.APPROVED
                || priceAmountVnd == null
                || priceAmountVnd <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Course is not available for purchase.");
        }
        Long teacherId = course.getTeacher() == null ? null : course.getTeacher().getId();
        return new CouponProduct(OrderItemProductType.COURSE, course.getId(), null, teacherId, course.getName(), priceAmountVnd);
    }

    public CouponProduct planProduct(PlanCode planCode) {
        if (planCode == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "planCode is required.");
        }
        PlanCatalogService.PlanCatalogItem plan = planCatalogService.get(planCode);
        return new CouponProduct(OrderItemProductType.PLAN, null, plan.planCode(), null, plan.name(), plan.priceVnd());
    }

    private CouponProduct planProduct(User user, PlanCode planCode) {
        if (planCode == PlanCode.PRO_PLUS && user.getAccountOrigin() != AccountOrigin.TEACHER_CREATED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PRO_PLUS is only available for teacher-created students.");
        }
        return planProduct(planCode);
    }

    private void validate(Coupon coupon, User user, CouponProduct product) {
        if (!coupon.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Coupon is not active.");
        }
        Instant now = Instant.now();
        if (now.isBefore(coupon.getValidFrom()) || !now.isBefore(coupon.getValidUntil())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Coupon is not valid.");
        }
        if (product.originalAmountVnd() < coupon.getMinOrderAmountVnd()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Order does not meet coupon minimum amount.");
        }
        if (!coupon.getProductScopes().contains(productScope(product))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Coupon is not eligible for this product.");
        }
        if (product.productType() == OrderItemProductType.COURSE) {
            if (!coupon.getCourseScopes().isEmpty()
                    && coupon.getCourseScopes().stream().map(CouponCourseScope::getCourseId).noneMatch(product.courseId()::equals)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Coupon is not eligible for this course.");
            }
            if (!coupon.getTeacherScopes().isEmpty()
                    && coupon.getTeacherScopes().stream().map(CouponTeacherScope::getTeacherId).noneMatch(product.teacherId()::equals)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Coupon is not eligible for this teacher.");
            }
        } else if (!coupon.getCourseScopes().isEmpty() || !coupon.getTeacherScopes().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Coupon is not eligible for this product.");
        }
        if (coupon.getUsageLimitTotal() != null
                && couponRedemptionRepository.countByCouponId(coupon.getId()) >= coupon.getUsageLimitTotal()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Coupon usage limit has been reached.");
        }
        if (coupon.getUsageLimitPerUser() != null
                && couponRedemptionRepository.countByCouponIdAndUser(coupon.getId(), user) >= coupon.getUsageLimitPerUser()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Coupon usage limit has been reached.");
        }
    }

    private CouponProductScope productScope(CouponProduct product) {
        if (product.productType() == OrderItemProductType.COURSE) {
            return CouponProductScope.COURSE_PURCHASE;
        }
        return CouponProductScope.valueOf(product.planCode().name());
    }

    private String normalize(String couponCode) {
        if (couponCode == null || couponCode.isBlank()) {
            return null;
        }
        return couponCode.trim().toUpperCase(Locale.ROOT);
    }
}
