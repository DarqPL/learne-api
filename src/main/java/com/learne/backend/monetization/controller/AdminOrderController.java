package com.learne.backend.monetization.controller;

import com.learne.backend.monetization.dto.OrderResponse;
import com.learne.backend.monetization.service.AdminOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
class AdminOrderController {
    private final AdminOrderService adminOrderService;

    AdminOrderController(AdminOrderService adminOrderService) {
        this.adminOrderService = adminOrderService;
    }

    @PostMapping("/{orderId}/mark-paid")
    OrderResponse markPaid(@PathVariable Long orderId) {
        return adminOrderService.markPaid(orderId);
    }

    @PostMapping("/{orderId}/simulate-sepay-payment")
    OrderResponse simulateSepayPayment(@PathVariable Long orderId) {
        return adminOrderService.simulateSepayPayment(orderId);
    }
}
