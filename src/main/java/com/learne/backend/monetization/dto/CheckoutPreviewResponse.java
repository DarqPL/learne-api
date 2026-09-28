package com.learne.backend.monetization.dto;

import com.learne.backend.monetization.enums.PlanCode;

public record CheckoutPreviewResponse(
        String productType,
        Long courseId,
        PlanCode planCode,
        String itemName,
        Long couponId,
        String couponCode,
        long originalAmountVnd,
        long discountAmountVnd,
        long finalAmountVnd) {
}
