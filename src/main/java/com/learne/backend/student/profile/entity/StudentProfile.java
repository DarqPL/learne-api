package com.learne.backend.student.profile.entity;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.profile.enums.StudentCurrentLevel;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "student_profiles")
public class StudentProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column
    private String bio;

    @Column(name = "learning_goal")
    private String learningGoal;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_level")
    private StudentCurrentLevel currentLevel;

    @Column(name = "daily_learning_minutes")
    private Integer dailyLearningMinutes;

    @Column(name = "preferred_skills_json")
    private String preferredSkillsJson;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "avatar_public_id")
    private String avatarPublicId;

    @Column(name = "public_profile_enabled", nullable = false)
    private boolean publicProfileEnabled;

    @Column(name = "show_email", nullable = false)
    private boolean showEmail;

    @Column(name = "show_phone_number", nullable = false)
    private boolean showPhoneNumber;

    @Column(name = "show_date_of_birth", nullable = false)
    private boolean showDateOfBirth;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public String getLearningGoal() { return learningGoal; }
    public void setLearningGoal(String learningGoal) { this.learningGoal = learningGoal; }
    public StudentCurrentLevel getCurrentLevel() { return currentLevel; }
    public void setCurrentLevel(StudentCurrentLevel currentLevel) { this.currentLevel = currentLevel; }
    public Integer getDailyLearningMinutes() { return dailyLearningMinutes; }
    public void setDailyLearningMinutes(Integer dailyLearningMinutes) { this.dailyLearningMinutes = dailyLearningMinutes; }
    public String getPreferredSkillsJson() { return preferredSkillsJson; }
    public void setPreferredSkillsJson(String preferredSkillsJson) { this.preferredSkillsJson = preferredSkillsJson; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getAvatarPublicId() { return avatarPublicId; }
    public void setAvatarPublicId(String avatarPublicId) { this.avatarPublicId = avatarPublicId; }
    public boolean isPublicProfileEnabled() { return publicProfileEnabled; }
    public void setPublicProfileEnabled(boolean publicProfileEnabled) { this.publicProfileEnabled = publicProfileEnabled; }
    public boolean isShowEmail() { return showEmail; }
    public void setShowEmail(boolean showEmail) { this.showEmail = showEmail; }
    public boolean isShowPhoneNumber() { return showPhoneNumber; }
    public void setShowPhoneNumber(boolean showPhoneNumber) { this.showPhoneNumber = showPhoneNumber; }
    public boolean isShowDateOfBirth() { return showDateOfBirth; }
    public void setShowDateOfBirth(boolean showDateOfBirth) { this.showDateOfBirth = showDateOfBirth; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
