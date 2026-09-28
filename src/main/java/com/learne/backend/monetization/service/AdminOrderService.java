package com.learne.backend.monetization.service;

import com.learne.backend.monetization.dto.OrderResponse;
import com.learne.backend.monetization.entity.Order;
import com.learne.backend.shared.api.ApiException;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOrderService {
    private final OrderPaymentSettlementService orderPaymentSettlementService;

    public AdminOrderService(OrderPaymentSettlementService orderPaymentSettlementService) {
        this.orderPaymentSettlementService = orderPaymentSettlementService;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public OrderResponse markPaid(Long orderId) {
        Order order = orderPaymentSettlementService.markPaid(orderId, Instant.now());
        return OrderResponse.from(order);
    }
}
