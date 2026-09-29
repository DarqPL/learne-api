package com.learne.backend.monetization.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.entity.Subscription;
import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.monetization.enums.SubscriptionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select subscription from Subscription subscription where subscription.id = :id")
    Optional<Subscription> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select count(subscription) > 0
            from Subscription subscription
            where subscription.user = :user
              and subscription.planCode = :planCode
              and subscription.status = com.learne.backend.monetization.enums.SubscriptionStatus.ACTIVE
              and subscription.startsAt <= :now
              and subscription.endsAt > :now
            """)
    boolean existsActivePlan(@Param("user") User user,
                             @Param("planCode") PlanCode planCode,
                             @Param("now") Instant now);

    default Optional<Subscription> findFirstByUserAndStatusAndEndsAtAfterOrderByEndsAtDesc(
            User user, SubscriptionStatus status, Instant now) {
        return findActiveByUserAndStatusOrderByEndsAtDesc(user, status, now, PageRequest.of(0, 1))
                .stream()
                .findFirst();
    }

    @Query("""
            select subscription
            from Subscription subscription
            where subscription.user = :user
              and subscription.status = :status
              and subscription.startsAt <= :now
              and subscription.endsAt > :now
            order by subscription.endsAt desc
            """)
    List<Subscription> findActiveByUserAndStatusOrderByEndsAtDesc(
            @Param("user") User user,
            @Param("status") SubscriptionStatus status,
            @Param("now") Instant now,
            Pageable pageable);

    @Query("""
            select subscription
            from Subscription subscription
            where subscription.user = :user
              and subscription.status = :status
              and subscription.startsAt <= :now
              and subscription.endsAt > :now
            """)
    List<Subscription> findByUserAndStatusAndEndsAtAfter(@Param("user") User user,
                                                        @Param("status") SubscriptionStatus status,
                                                        @Param("now") Instant now);
}
