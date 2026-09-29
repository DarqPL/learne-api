package com.learne.backend.monetization.repository;

import com.learne.backend.monetization.entity.SepayWebhookEvent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SepayWebhookEventRepository extends JpaRepository<SepayWebhookEvent, Long> {
    Optional<SepayWebhookEvent> findBySepayTransactionId(Long sepayTransactionId);
}
