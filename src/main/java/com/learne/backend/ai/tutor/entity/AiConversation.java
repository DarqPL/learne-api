package com.learne.backend.ai.tutor.entity;

import com.learne.backend.ai.tutor.enums.AiConversationStatus;
import com.learne.backend.ai.tutor.enums.AiTutorPracticeType;
import com.learne.backend.domain.auth.User;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "ai_conversations")
public class AiConversation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "practice_type", nullable = false, length = 50)
    private AiTutorPracticeType practiceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AiConversationStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    @Column(name = "cleanup_warn_at", nullable = false)
    private Instant cleanupWarnAt;

    @Column(name = "archive_after", nullable = false)
    private Instant archiveAfter;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "delete_after")
    private Instant deleteAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AiMessage> messages = new ArrayList<>();

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (startedAt == null) {
            startedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public AiTutorPracticeType getPracticeType() { return practiceType; }
    public void setPracticeType(AiTutorPracticeType practiceType) { this.practiceType = practiceType; }
    public AiConversationStatus getStatus() { return status; }
    public void setStatus(AiConversationStatus status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(Instant lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public Instant getCleanupWarnAt() { return cleanupWarnAt; }
    public void setCleanupWarnAt(Instant cleanupWarnAt) { this.cleanupWarnAt = cleanupWarnAt; }
    public Instant getArchiveAfter() { return archiveAfter; }
    public void setArchiveAfter(Instant archiveAfter) { this.archiveAfter = archiveAfter; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }
    public Instant getDeleteAfter() { return deleteAfter; }
    public void setDeleteAfter(Instant deleteAfter) { this.deleteAfter = deleteAfter; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<AiMessage> getMessages() { return Collections.unmodifiableList(messages); }
    public void addMessage(AiMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        AiConversation previousConversation = message.getConversation();
        if (previousConversation != null && previousConversation != this) {
            previousConversation.removeMessage(message);
        }
        if (!messages.contains(message)) {
            messages.add(message);
        }
        message.setConversationInternal(this);
    }
    public void removeMessage(AiMessage message) {
        if (messages.remove(message)) {
            message.setConversationInternal(null);
        }
    }
}
