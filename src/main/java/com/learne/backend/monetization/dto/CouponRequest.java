package com.learne.backend.monetization.dto;

import com.learne.backend.monetization.enums.CouponDiscountType;
import com.learne.backend.monetization.enums.CouponProductScope;
import java.time.Instant;
import java.util.List;

public record CouponRequest(
        String code,
        CouponDiscountType discountType,
        Long discountValue,
        Long minOrderAmountVnd,
        Long maxDiscountAmountVnd,
        Long minimumFinalAmountVnd,
        Integer usageLimitTotal,
        Integer usageLimitPerUser,
        Instant validFrom,
        Instant validUntil,
        Boolean active,
        List<CouponProductScope> productScopes,
        List<Long> courseIds,
        List<Long> teacherIds) {
}
