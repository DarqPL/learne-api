package com.learne.backend.ai.tutor.entity;

import com.learne.backend.ai.tutor.enums.AiTutorFeedbackType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ai_feedback")
public class AiFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false, unique = true)
    private AiMessage message;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", nullable = false, length = 50)
    private AiTutorFeedbackType errorType;

    @Column(name = "original_text", nullable = false, length = 1000)
    private String originalText;

    @Column(name = "corrected_text", nullable = false, length = 1000)
    private String correctedText;

    @Column(nullable = false, length = 1000)
    private String explanation;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public AiMessage getMessage() { return message; }
    public void setMessage(AiMessage message) {
        if (this.message == message) {
            return;
        }
        AiMessage previous = this.message;
        this.message = message;
        if (previous != null && previous.getFeedback() == this) {
            previous.setFeedback(null);
        }
        if (message != null && message.getFeedback() != this) {
            message.setFeedback(this);
        }
    }
    public AiTutorFeedbackType getErrorType() { return errorType; }
    public void setErrorType(AiTutorFeedbackType errorType) { this.errorType = errorType; }
    public String getOriginalText() { return originalText; }
    public void setOriginalText(String originalText) { this.originalText = originalText; }
    public String getCorrectedText() { return correctedText; }
    public void setCorrectedText(String correctedText) { this.correctedText = correctedText; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public Instant getCreatedAt() { return createdAt; }
}
