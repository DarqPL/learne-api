package com.learne.backend.monetization.entity;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.enums.AiUsageFeature;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ai_usage_events")
public class AiUsageEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private AiUsageFeature feature;

    @Column(name = "used_at", nullable = false)
    private Instant usedAt;

    @Column(name = "request_id", length = 120)
    private String requestId;

    @PrePersist
    void prePersist() {
        if (usedAt == null) {
            usedAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public AiUsageFeature getFeature() { return feature; }
    public void setFeature(AiUsageFeature feature) { this.feature = feature; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
