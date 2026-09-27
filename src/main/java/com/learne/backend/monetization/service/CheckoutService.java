package com.learne.backend.monetization.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.dto.OrderResponse;
import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.entity.OrderItem;
import com.learne.backend.monetization.enums.OrderItemProductType;
import com.learne.backend.monetization.enums.OrderStatus;
import com.learne.backend.monetization.enums.OrderType;
import com.learne.backend.monetization.enums.PaymentProvider;
import com.learne.backend.monetization.repository.OrderRepository;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CoursePriceStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutService {
    private final CourseRepository courseRepository;
    private final OrderRepository orderRepository;
    private final CourseAccessService courseAccessService;

    public CheckoutService(CourseRepository courseRepository,
                           OrderRepository orderRepository,
                           CourseAccessService courseAccessService) {
        this.courseRepository = courseRepository;
        this.orderRepository = orderRepository;
        this.courseAccessService = courseAccessService;
    }

    @Transactional
    public OrderResponse createCourseOrder(User student, Long courseId) {
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

        Order order = new Order();
        order.setUser(student);
        order.setOrderType(OrderType.COURSE_PURCHASE);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setPaymentProvider(PaymentProvider.INTERNAL);
        order.setOriginalAmountVnd(priceAmountVnd);
        order.setDiscountAmountVnd(0L);
        order.setFinalAmountVnd(priceAmountVnd);
        order.setExpiresAt(Instant.now().plusSeconds(900));

        OrderItem item = new OrderItem();
        item.setProductType(OrderItemProductType.COURSE);
        item.setCourse(course);
        item.setItemNameSnapshot(course.getName());
        item.setUnitAmountVnd(priceAmountVnd);
        item.setQuantity(1);
        item.setTotalAmountVnd(priceAmountVnd);
        order.addItem(item);

        return OrderResponse.from(orderRepository.save(order));
    }
}
