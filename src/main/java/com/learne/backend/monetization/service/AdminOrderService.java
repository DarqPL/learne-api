package com.learne.backend.monetization.service;

import com.learne.backend.monetization.dto.OrderResponse;
import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.enums.PaymentProvider;
import com.learne.backend.monetization.repository.OrderRepository;
import com.learne.backend.shared.api.ApiException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOrderService {
    private final OrderPaymentSettlementService orderPaymentSettlementService;
    private final OrderRepository orderRepository;
    private final SepayPaymentProperties sepayPaymentProperties;

    public AdminOrderService(OrderPaymentSettlementService orderPaymentSettlementService,
                             OrderRepository orderRepository,
                             SepayPaymentProperties sepayPaymentProperties) {
        this.orderPaymentSettlementService = orderPaymentSettlementService;
        this.orderRepository = orderRepository;
        this.sepayPaymentProperties = sepayPaymentProperties;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public OrderResponse markPaid(Long orderId) {
        Order order = orderPaymentSettlementService.markPaid(orderId, Instant.now());
        return OrderResponse.from(order);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public OrderResponse simulateSepayPayment(Long orderId) {
        if (!sepayPaymentProperties.mockEnabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Payment simulation is disabled.");
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Order not found."));
        if (order.getPaymentProvider() != PaymentProvider.SEPAY || isBlank(order.getPaymentReference())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only SEPAY orders can be simulated.");
        }
        var result = orderPaymentSettlementService.settleSepayPayment(
                order.getPaymentReference(),
                order.getFinalAmountVnd(),
                Instant.now());
        if (!result.processed()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, result.ignoredReason());
        }
        order = result.order();
        return OrderResponse.from(order);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
