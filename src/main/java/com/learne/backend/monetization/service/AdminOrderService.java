package com.learne.backend.monetization.service;

import com.learne.backend.monetization.dto.OrderResponse;
import com.learne.backend.monetization.entity.CourseEntitlement;
import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.entity.OrderItem;
import com.learne.backend.monetization.enums.CourseEntitlementSource;
import com.learne.backend.monetization.enums.OrderStatus;
import com.learne.backend.monetization.repository.CourseEntitlementRepository;
import com.learne.backend.monetization.repository.OrderRepository;
import com.learne.backend.shared.api.ApiException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOrderService {
    private final OrderRepository orderRepository;
    private final CourseEntitlementRepository courseEntitlementRepository;

    public AdminOrderService(OrderRepository orderRepository,
                             CourseEntitlementRepository courseEntitlementRepository) {
        this.orderRepository = orderRepository;
        this.courseEntitlementRepository = courseEntitlementRepository;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public OrderResponse markPaid(Long orderId) {
        Instant now = Instant.now();
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Order not found."));
        if (order.getStatus() == OrderStatus.PAID) {
            return OrderResponse.from(order);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only pending orders can be marked paid.");
        }
        if (order.getExpiresAt().isBefore(now)) {
            order.setStatus(OrderStatus.EXPIRED);
            order.setDedupeKey(null);
            orderRepository.save(order);
            throw new ApiException(HttpStatus.BAD_REQUEST, "Order has expired.");
        }

        Instant paidAt = now;
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(paidAt);
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

        return OrderResponse.from(orderRepository.save(order));
    }
}
