package com.learne.backend.monetization.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.dto.AiQuotaSnapshot;
import com.learne.backend.monetization.dto.SubscriptionSummaryResponse;
import com.learne.backend.monetization.entity.Subscription;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.monetization.enums.SubscriptionStatus;
import com.learne.backend.monetization.repository.SubscriptionRepository;
import com.learne.backend.monetization.service.AiUsageGuard;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student/subscription")
class StudentSubscriptionController {
    private final AiUsageGuard aiUsageGuard;
    private final Clock clock;
    private final SubscriptionRepository subscriptionRepository;

    StudentSubscriptionController(AiUsageGuard aiUsageGuard, Clock clock, SubscriptionRepository subscriptionRepository) {
        this.aiUsageGuard = aiUsageGuard;
        this.clock = clock;
        this.subscriptionRepository = subscriptionRepository;
    }

    @GetMapping
    SubscriptionSummaryResponse summary(Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        Subscription subscription = subscriptionRepository.findByUserAndStatusAndEndsAtAfter(
                        student,
                        SubscriptionStatus.ACTIVE,
                        Instant.now(clock))
                .stream()
                .max(Comparator.comparingInt(subscriptionItem -> planRank(subscriptionItem.getPlanCode())))
                .orElse(null);
        PlanCode planCode = subscription == null ? null : subscription.getPlanCode();
        AiQuotaSnapshot quotaSnapshot = aiUsageGuard.snapshot(student);
        return new SubscriptionSummaryResponse(
                planCode,
                quotaSnapshot.dailyLimit(),
                quotaSnapshot.usedToday(),
                subscription == null ? null : subscription.getStartsAt(),
                subscription == null ? null : subscription.getEndsAt());
    }

    private int planRank(PlanCode planCode) {
        return switch (planCode) {
            case PRO_PLUS -> 3;
            case PRO -> 2;
            case VIP -> 1;
        };
    }
}
