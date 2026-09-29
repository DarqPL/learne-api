package com.learne.backend.monetization.dto;

import com.learne.backend.monetization.enums.PlanCode;
import java.time.Instant;

public record SubscriptionSummaryResponse(
        PlanCode planCode,
        int aiDailyLimit,
        int aiUsedToday,
        Instant startsAt,
        Instant endsAt
) {
}
