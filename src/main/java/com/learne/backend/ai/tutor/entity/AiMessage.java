package com.learne.backend.ai.tutor.entity;

import com.learne.backend.ai.tutor.enums.AiMessageSenderType;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ai_messages")
public class AiMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private AiConversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 20)
    private AiMessageSenderType senderType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToOne(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    private AiFeedback feedback;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public AiConversation getConversation() { return conversation; }
    void setConversationInternal(AiConversation conversation) { this.conversation = conversation; }
    public AiMessageSenderType getSenderType() { return senderType; }
    public void setSenderType(AiMessageSenderType senderType) { this.senderType = senderType; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getCreatedAt() { return createdAt; }
    public AiFeedback getFeedback() { return feedback; }
    public void setFeedback(AiFeedback feedback) {
        if (this.feedback == feedback) {
            return;
        }
        AiFeedback previous = this.feedback;
        this.feedback = feedback;
        if (previous != null && previous.getMessage() == this) {
            previous.setMessage(null);
        }
        if (feedback != null && feedback.getMessage() != this) {
            feedback.setMessage(this);
        }
    }
}
