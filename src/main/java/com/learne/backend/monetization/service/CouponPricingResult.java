package com.learne.backend.monetization.service;

public record CouponPricingResult(
        Long couponId,
        String couponCode,
        long originalAmountVnd,
        long discountAmountVnd,
        long finalAmountVnd) {
}
