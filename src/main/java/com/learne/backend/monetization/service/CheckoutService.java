package com.learne.backend.monetization.service;

import com.learne.backend.domain.auth.AccountOrigin;
import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.dto.OrderResponse;
import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.entity.OrderItem;
import com.learne.backend.monetization.enums.OrderItemProductType;
import com.learne.backend.monetization.enums.OrderStatus;
import com.learne.backend.monetization.enums.OrderType;
import com.learne.backend.monetization.enums.PaymentProvider;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.monetization.repository.OrderRepository;
import com.learne.backend.monetization.repository.SubscriptionRepository;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CoursePriceStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutService {
    private final CourseRepository courseRepository;
    private final OrderRepository orderRepository;
    private final CourseAccessService courseAccessService;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanCatalogService planCatalogService;

    public CheckoutService(CourseRepository courseRepository,
                            OrderRepository orderRepository,
                            CourseAccessService courseAccessService,
                            SubscriptionRepository subscriptionRepository,
                            PlanCatalogService planCatalogService) {
        this.courseRepository = courseRepository;
        this.orderRepository = orderRepository;
        this.courseAccessService = courseAccessService;
        this.subscriptionRepository = subscriptionRepository;
        this.planCatalogService = planCatalogService;
    }

    @Transactional
    public OrderResponse createCourseOrder(User student, Long courseId) {
        Instant now = Instant.now();
        Course course = courseRepository.findStudentDetailByIdAndStatus(courseId, ContentStatus.APPROVED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
        if (courseAccessService.canEnroll(student, course)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Course is already accessible.");
        }
        Long priceAmountVnd = course.getPriceAmountVnd();
        if (course.getPricingType() != CoursePricingType.PAID
                || course.getPriceStatus() != CoursePriceStatus.APPROVED
                || priceAmountVnd == null
                || priceAmountVnd <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Course is not available for purchase.");
        }
        String dedupeKey = coursePurchaseDedupeKey(student.getId(), course.getId());
        List<Order> duplicateOrders = orderRepository.findByDedupeKeyForUpdate(dedupeKey);
        boolean hasBlockingDuplicate = false;
        for (Order duplicateOrder : duplicateOrders) {
            if (duplicateOrder.getStatus() == OrderStatus.PENDING_PAYMENT && !duplicateOrder.getExpiresAt().isAfter(now)) {
                duplicateOrder.setStatus(OrderStatus.EXPIRED);
                duplicateOrder.setDedupeKey(null);
            } else {
                hasBlockingDuplicate = true;
            }
        }
        if (!duplicateOrders.isEmpty()) {
            orderRepository.saveAllAndFlush(duplicateOrders);
        }
        if (hasBlockingDuplicate
                || orderRepository.existsPendingUnexpiredCourseOrder(student, course, OrderStatus.PENDING_PAYMENT, now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pending order already exists.");
        }

        Order order = new Order();
        order.setUser(student);
        order.setDedupeKey(dedupeKey);
        order.setOrderType(OrderType.COURSE_PURCHASE);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setPaymentProvider(PaymentProvider.INTERNAL);
        order.setOriginalAmountVnd(priceAmountVnd);
        order.setDiscountAmountVnd(0L);
        order.setFinalAmountVnd(priceAmountVnd);
        order.setExpiresAt(now.plusSeconds(900));

        OrderItem item = new OrderItem();
        item.setProductType(OrderItemProductType.COURSE);
        item.setCourse(course);
        item.setItemNameSnapshot(course.getName());
        item.setUnitAmountVnd(priceAmountVnd);
        item.setQuantity(1);
        item.setTotalAmountVnd(priceAmountVnd);
        order.addItem(item);

        try {
            return OrderResponse.from(orderRepository.saveAndFlush(order));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pending order already exists.");
        }
    }

    @Transactional
    public OrderResponse createPlanOrder(User student, PlanCode planCode) {
        Instant now = Instant.now();
        if (planCode == PlanCode.PRO_PLUS && student.getAccountOrigin() != AccountOrigin.TEACHER_CREATED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PRO_PLUS is only available for teacher-created students.");
        }
        if (subscriptionRepository.existsActivePlan(student, planCode, now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Plan is already active.");
        }

        String dedupeKey = planSubscriptionDedupeKey(student.getId(), planCode);
        List<Order> duplicateOrders = orderRepository.findByDedupeKeyForUpdate(dedupeKey);
        boolean hasBlockingDuplicate = false;
        for (Order duplicateOrder : duplicateOrders) {
            if (duplicateOrder.getStatus() == OrderStatus.PENDING_PAYMENT && !duplicateOrder.getExpiresAt().isAfter(now)) {
                duplicateOrder.setStatus(OrderStatus.EXPIRED);
                duplicateOrder.setDedupeKey(null);
            } else if (duplicateOrder.getStatus() == OrderStatus.PAID) {
                duplicateOrder.setDedupeKey(null);
            } else {
                hasBlockingDuplicate = true;
            }
        }
        if (!duplicateOrders.isEmpty()) {
            orderRepository.saveAllAndFlush(duplicateOrders);
        }
        if (hasBlockingDuplicate) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pending order already exists.");
        }

        PlanCatalogService.PlanCatalogItem plan = planCatalogService.get(planCode);
        Order order = new Order();
        order.setUser(student);
        order.setDedupeKey(dedupeKey);
        order.setOrderType(OrderType.PLAN_SUBSCRIPTION);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setPaymentProvider(PaymentProvider.INTERNAL);
        order.setOriginalAmountVnd(plan.priceVnd());
        order.setDiscountAmountVnd(0L);
        order.setFinalAmountVnd(plan.priceVnd());
        order.setExpiresAt(now.plusSeconds(900));

        OrderItem item = new OrderItem();
        item.setProductType(OrderItemProductType.PLAN);
        item.setPlanCode(plan.planCode());
        item.setItemNameSnapshot(plan.name());
        item.setUnitAmountVnd(plan.priceVnd());
        item.setQuantity(1);
        item.setTotalAmountVnd(plan.priceVnd());
        order.addItem(item);

        try {
            return OrderResponse.from(orderRepository.saveAndFlush(order));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pending order already exists.");
        }
    }

    private String coursePurchaseDedupeKey(Long userId, Long courseId) {
        return "COURSE:" + userId + ":" + courseId;
    }

    private String planSubscriptionDedupeKey(Long userId, PlanCode planCode) {
        return "PLAN:" + userId + ":" + planCode;
    }
}
