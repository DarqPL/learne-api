package com.learne.backend.monetization.service;

import com.learne.backend.monetization.enums.OrderItemProductType;
import com.learne.backend.monetization.enums.PlanCode;

public record CouponProduct(
        OrderItemProductType productType,
        Long courseId,
        PlanCode planCode,
        Long teacherId,
        String itemName,
        long originalAmountVnd) {
}
