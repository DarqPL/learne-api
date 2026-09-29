package com.learne.backend.monetization.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.monetization.dto.AiQuotaSnapshot;
import com.learne.backend.monetization.entity.AiUsageEvent;
import com.learne.backend.monetization.entity.Subscription;
import com.learne.backend.monetization.enums.AiUsageFeature;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.monetization.enums.SubscriptionStatus;
import com.learne.backend.monetization.repository.AiUsageEventRepository;
import com.learne.backend.monetization.repository.CourseEntitlementRepository;
import com.learne.backend.monetization.repository.SubscriptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiUsageGuard {
    private final AiUsageEventRepository aiUsageEventRepository;
    private final Clock clock;
    private final CourseEntitlementRepository courseEntitlementRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    public AiUsageGuard(AiUsageEventRepository aiUsageEventRepository,
                        Clock clock,
                        CourseEntitlementRepository courseEntitlementRepository,
                        SubscriptionRepository subscriptionRepository,
                        UserRepository userRepository) {
        this.aiUsageEventRepository = aiUsageEventRepository;
        this.clock = clock;
        this.courseEntitlementRepository = courseEntitlementRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public AiQuotaSnapshot snapshot(User user) {
        assertAllowed(user);
        Instant now = Instant.now(clock);
        return new AiQuotaSnapshot(dailyLimit(user, now), usedToday(user, now));
    }

    @Transactional
    public AiQuotaSnapshot recordAcceptedUsage(User user, AiUsageFeature feature) {
        User lockedUser = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not allowed to use AI features."));
        assertAllowed(lockedUser);
        Instant now = Instant.now(clock);
        int dailyLimit = dailyLimit(lockedUser, now);
        int usedToday = usedToday(lockedUser, now);
        if (usedToday >= dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI daily quota reached.");
        }

        AiUsageEvent event = new AiUsageEvent();
        event.setUser(lockedUser);
        event.setFeature(feature);
        event.setUsedAt(now);
        aiUsageEventRepository.save(event);

        return new AiQuotaSnapshot(dailyLimit, usedToday(lockedUser, now));
    }

    private void assertAllowed(User user) {
        if (user.isLocked() || !user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not allowed to use AI features.");
        }
    }

    private int dailyLimit(User user, Instant now) {
        PlanCode planCode = activePlan(user, now);
        if (planCode != null) {
            return switch (planCode) {
                case VIP -> 50;
                case PRO, PRO_PLUS -> 150;
            };
        }
        if (courseEntitlementRepository.existsActiveCoursePurchaseEntitlement(user, now)) {
            return 10;
        }
        return 5;
    }

    private PlanCode activePlan(User user, Instant now) {
        return subscriptionRepository.findByUserAndStatusAndEndsAtAfter(user, SubscriptionStatus.ACTIVE, now)
                .stream()
                .max(Comparator.comparingInt(subscription -> planRank(subscription.getPlanCode())))
                .map(Subscription::getPlanCode)
                .orElse(null);
    }

    private int planRank(PlanCode planCode) {
        return switch (planCode) {
            case PRO_PLUS -> 3;
            case PRO -> 2;
            case VIP -> 1;
        };
    }

    private int usedToday(User user, Instant now) {
        Instant startInclusive = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endExclusive = startInclusive.plusSeconds(86_400);
        return Math.toIntExact(aiUsageEventRepository.countByUserAndUsedAtGreaterThanEqualAndUsedAtLessThan(
                user,
                startInclusive,
                endExclusive));
    }
}
