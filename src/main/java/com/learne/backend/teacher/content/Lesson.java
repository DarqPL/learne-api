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
@Table(name = "lessons")
class Lesson {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_id")
    private Topic topic;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id")
    private User teacher;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "skill_type", nullable = false)
    private SkillType skillType;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(name = "xp_reward", nullable = false)
    private int xpReward;

    @Column(name = "is_locked", nullable = false)
    private boolean locked;

    @Column(name = "is_placement_test", nullable = false)
    private boolean placementTest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentStatus status;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "lesson", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Question> questions = new ArrayList<>();

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    Long getId() { return id; }
    Topic getTopic() { return topic; }
    void setTopic(Topic topic) { this.topic = topic; }
    User getTeacher() { return teacher; }
    void setTeacher(User teacher) { this.teacher = teacher; }
    String getTitle() { return title; }
    void setTitle(String title) { this.title = title; }
    String getDescription() { return description; }
    void setDescription(String description) { this.description = description; }
    String getContent() { return content; }
    void setContent(String content) { this.content = content; }
    SkillType getSkillType() { return skillType; }
    void setSkillType(SkillType skillType) { this.skillType = skillType; }
    int getOrderIndex() { return orderIndex; }
    void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }
    int getXpReward() { return xpReward; }
    void setXpReward(int xpReward) { this.xpReward = xpReward; }
    boolean isPlacementTest() { return placementTest; }
    void setPlacementTest(boolean placementTest) { this.placementTest = placementTest; }
    ContentStatus getStatus() { return status; }
    void setStatus(ContentStatus status) { this.status = status; }
    String getRejectionReason() { return rejectionReason; }
    List<Question> getQuestions() { return questions; }
}
