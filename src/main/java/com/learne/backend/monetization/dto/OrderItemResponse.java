package com.learne.backend.monetization.dto;

import com.learne.backend.monetization.entity.OrderItem;
import com.learne.backend.monetization.enums.OrderItemProductType;

public record OrderItemResponse(
        Long id,
        OrderItemProductType productType,
        Long courseId,
        String itemNameSnapshot,
        Long unitAmountVnd,
        Integer quantity,
        Long totalAmountVnd
) {
    public static OrderItemResponse from(OrderItem item) {
        Long courseId = item.getCourse() == null ? null : item.getCourse().getId();
        return new OrderItemResponse(
                item.getId(),
                item.getProductType(),
                courseId,
                item.getItemNameSnapshot(),
                item.getUnitAmountVnd(),
                item.getQuantity(),
                item.getTotalAmountVnd());
    }
}
