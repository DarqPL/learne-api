package com.learne.backend.monetization.dto;

import com.learne.backend.monetization.enums.PlanCode;

public record CheckoutPreviewRequest(
        String productType,
        Long courseId,
        PlanCode planCode,
        String couponCode) {
}
