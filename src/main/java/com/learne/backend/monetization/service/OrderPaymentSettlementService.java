package com.learne.backend.monetization.service;

import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.monetization.entity.Coupon;
import com.learne.backend.monetization.entity.CouponRedemption;
import com.learne.backend.monetization.entity.CourseEntitlement;
import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.entity.OrderItem;
import com.learne.backend.monetization.entity.Subscription;
import com.learne.backend.monetization.enums.CourseEntitlementSource;
import com.learne.backend.monetization.enums.OrderStatus;
import com.learne.backend.monetization.enums.OrderType;
import com.learne.backend.monetization.enums.SubscriptionStatus;
import com.learne.backend.monetization.repository.CouponRedemptionRepository;
import com.learne.backend.monetization.repository.CouponRepository;
import com.learne.backend.monetization.repository.CourseEntitlementRepository;
import com.learne.backend.monetization.repository.OrderRepository;
import com.learne.backend.monetization.repository.SubscriptionRepository;
import com.learne.backend.shared.api.ApiException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderPaymentSettlementService {
    private final OrderRepository orderRepository;
    private final CourseEntitlementRepository courseEntitlementRepository;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository couponRedemptionRepository;

    public OrderPaymentSettlementService(OrderRepository orderRepository,
                                         CourseEntitlementRepository courseEntitlementRepository,
                                         UserRepository userRepository,
                                         SubscriptionRepository subscriptionRepository,
                                         CouponRepository couponRepository,
                                         CouponRedemptionRepository couponRedemptionRepository) {
        this.orderRepository = orderRepository;
        this.courseEntitlementRepository = courseEntitlementRepository;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.couponRepository = couponRepository;
        this.couponRedemptionRepository = couponRedemptionRepository;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Order markPaid(Long orderId, Instant paidAt) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Order not found."));
        return settleLockedOrder(order, paidAt);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public SepaySettlementResult settleSepayPayment(String paymentReference, Long amountVnd, Instant paidAt) {
        Order order = orderRepository.findByPaymentReferenceForUpdate(paymentReference).orElse(null);
        if (order == null) {
            return new SepaySettlementResult(null, false, "Payment reference did not match an order.");
        }
        if (!Long.valueOf(order.getFinalAmountVnd()).equals(amountVnd)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Insufficient amount or amount mismatch.");
        }
        if (order.getStatus() == OrderStatus.PAID) {
            return new SepaySettlementResult(order, true, null);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return new SepaySettlementResult(order, false, "Order is not pending payment.");
        }
        return new SepaySettlementResult(settleLockedOrder(order, paidAt), true, null);
    }

    private Order settleLockedOrder(Order order, Instant paidAt) {
        if (order.getStatus() == OrderStatus.PAID) {
            return order;
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only pending orders can be marked paid.");
        }
        if (order.getExpiresAt().isBefore(paidAt)) {
            order.setStatus(OrderStatus.EXPIRED);
            order.setDedupeKey(null);
            orderRepository.save(order);
            throw new ApiException(HttpStatus.BAD_REQUEST, "Order has expired.");
        }

        userRepository.findByIdForUpdate(order.getUser().getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Order user not found."));
        Coupon coupon = lockAndValidateCouponUsage(order);
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(paidAt);
        if (order.getOrderType() == OrderType.PLAN_SUBSCRIPTION) {
            createSubscription(order, paidAt);
            createCouponRedemptionIfNeeded(order, coupon, paidAt);
            return orderRepository.save(order);
        }
        for (OrderItem item : order.getItems()) {
            if (item.getCourse() == null) {
                continue;
            }
            if (courseEntitlementRepository.existsActiveCourseAccess(order.getUser(), item.getCourse(), paidAt)) {
                continue;
            }
            CourseEntitlement entitlement = new CourseEntitlement();
            entitlement.setUser(order.getUser());
            entitlement.setCourse(item.getCourse());
            entitlement.setOrder(order);
            entitlement.setSource(CourseEntitlementSource.COURSE_PURCHASE);
            entitlement.setStartsAt(paidAt);
            courseEntitlementRepository.save(entitlement);
        }
        createCouponRedemptionIfNeeded(order, coupon, paidAt);

        return orderRepository.save(order);
    }

    public record SepaySettlementResult(Order order, boolean processed, String ignoredReason) {
    }

    private Coupon lockAndValidateCouponUsage(Order order) {
        if (order.getCouponId() == null || order.getDiscountAmountVnd() == null || order.getDiscountAmountVnd() <= 0) {
            return null;
        }
        if (couponRedemptionRepository.existsByOrderId(order.getId())) {
            return null;
        }
        Coupon coupon = couponRepository.findByIdForUpdate(order.getCouponId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Coupon not found."));
        if (coupon.getUsageLimitTotal() != null
                && couponRedemptionRepository.countByCouponId(coupon.getId()) >= coupon.getUsageLimitTotal()) {
            throw new ApiException(HttpStatus.CONFLICT, "Coupon usage limit has been reached.");
        }
        if (coupon.getUsageLimitPerUser() != null
                && couponRedemptionRepository.countByCouponIdAndUser(coupon.getId(), order.getUser()) >= coupon.getUsageLimitPerUser()) {
            throw new ApiException(HttpStatus.CONFLICT, "Coupon usage limit has been reached.");
        }
        return coupon;
    }

    private void createCouponRedemptionIfNeeded(Order order, Coupon coupon, Instant paidAt) {
        if (coupon == null || couponRedemptionRepository.existsByOrderId(order.getId())) {
            return;
        }
        CouponRedemption redemption = new CouponRedemption();
        redemption.setCoupon(coupon);
        redemption.setUser(order.getUser());
        redemption.setOrder(order);
        redemption.setDiscountAmountVnd(order.getDiscountAmountVnd());
        redemption.setRedeemedAt(paidAt);
        couponRedemptionRepository.save(redemption);
    }

    private void createSubscription(Order order, Instant paidAt) {
        for (OrderItem item : order.getItems()) {
            if (item.getPlanCode() == null
                    || subscriptionRepository.existsActivePlan(order.getUser(), item.getPlanCode(), paidAt)) {
                continue;
            }
            Subscription subscription = new Subscription();
            subscription.setUser(order.getUser());
            subscription.setPlanCode(item.getPlanCode());
            subscription.setStatus(SubscriptionStatus.ACTIVE);
            subscription.setStartsAt(paidAt);
            subscription.setEndsAt(paidAt.plusSeconds(30L * 24 * 60 * 60));
            subscription.setOrder(order);
            subscriptionRepository.save(subscription);
        }
    }
}
