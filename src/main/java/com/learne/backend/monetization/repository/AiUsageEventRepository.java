package com.learne.backend.monetization.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.entity.AiUsageEvent;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiUsageEventRepository extends JpaRepository<AiUsageEvent, Long> {
    long countByUserAndUsedAtGreaterThanEqualAndUsedAtLessThan(
            User user,
            Instant startInclusive,
            Instant endExclusive);
}
