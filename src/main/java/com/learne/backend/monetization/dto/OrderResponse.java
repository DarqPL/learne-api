package com.learne.backend.monetization.dto;

import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.enums.OrderStatus;
import com.learne.backend.monetization.enums.OrderType;
import com.learne.backend.monetization.enums.PaymentProvider;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        OrderType orderType,
        OrderStatus status,
        Long originalAmountVnd,
        Long discountAmountVnd,
        Long finalAmountVnd,
        Long couponId,
        PaymentProvider paymentProvider,
        String paymentReference,
        Instant paidAt,
        Instant expiresAt,
        List<OrderItemResponse> items
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderType(),
                order.getStatus(),
                order.getOriginalAmountVnd(),
                order.getDiscountAmountVnd(),
                order.getFinalAmountVnd(),
                order.getCouponId(),
                order.getPaymentProvider(),
                order.getPaymentReference(),
                order.getPaidAt(),
                order.getExpiresAt(),
                order.getItems().stream().map(OrderItemResponse::from).toList());
    }
}
