package com.learne.backend.monetization.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.dto.OrderResponse;
import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.repository.OrderRepository;
import com.learne.backend.monetization.service.PaymentInstruction;
import com.learne.backend.monetization.service.SepayPaymentService;
import com.learne.backend.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student/orders")
class StudentOrderController {
    private final OrderRepository orderRepository;
    private final SepayPaymentService sepayPaymentService;

    StudentOrderController(OrderRepository orderRepository, SepayPaymentService sepayPaymentService) {
        this.orderRepository = orderRepository;
        this.sepayPaymentService = sepayPaymentService;
    }

    @GetMapping("/{orderId}")
    @Transactional(readOnly = true)
    OrderResponse getOrder(@PathVariable Long orderId, Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        Order order = orderRepository.findByIdAndUser(orderId, student)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Order not found."));
        PaymentInstruction instruction = sepayPaymentService.instructionFor(order);
        return OrderResponse.from(order, instruction);
    }
}
