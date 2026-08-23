package com.learne.backend.teacher.content;

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
import java.util.List;

@Entity
@Table(name = "courses")
class Course {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id")
    private User teacher;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CourseType type;

    @Column(nullable = false)
    private String level;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentStatus status;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Topic> topics = new ArrayList<>();

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    Long getId() { return id; }
    User getTeacher() { return teacher; }
    void setTeacher(User teacher) { this.teacher = teacher; }
    String getName() { return name; }
    void setName(String name) { this.name = name; }
    String getDescription() { return description; }
    void setDescription(String description) { this.description = description; }
    CourseType getType() { return type; }
    void setType(CourseType type) { this.type = type; }
    String getLevel() { return level; }
    void setLevel(String level) { this.level = level; }
    int getOrderIndex() { return orderIndex; }
    void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }
    ContentStatus getStatus() { return status; }
    void setStatus(ContentStatus status) { this.status = status; }
    String getRejectionReason() { return rejectionReason; }
    void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    Instant getReviewedAt() { return reviewedAt; }
    Instant getPublishedAt() { return publishedAt; }
    List<Topic> getTopics() { return topics; }
}
