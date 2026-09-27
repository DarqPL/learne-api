package com.learne.backend.monetization.entity;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.entity.Course;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "vip_course_selections")
public class VipCourseSelection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id")
    private Subscription subscription;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id")
    private Course course;

    @Column(nullable = false, length = 50)
    private String level;

    @Column(name = "selected_at", nullable = false)
    private Instant selectedAt;

    @Column(name = "replaced_at")
    private Instant replacedAt;

    @PrePersist
    void prePersist() {
        if (selectedAt == null) {
            selectedAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Subscription getSubscription() { return subscription; }
    public void setSubscription(Subscription subscription) { this.subscription = subscription; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Instant getSelectedAt() { return selectedAt; }
    public void setSelectedAt(Instant selectedAt) { this.selectedAt = selectedAt; }
    public Instant getReplacedAt() { return replacedAt; }
    public void setReplacedAt(Instant replacedAt) { this.replacedAt = replacedAt; }
}
