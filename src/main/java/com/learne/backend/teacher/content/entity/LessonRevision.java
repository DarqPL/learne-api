package com.learne.backend.teacher.content.entity;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.enums.SkillType;
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
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "lesson_revisions")
public class LessonRevision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_revision_id")
    private TopicRevision topicRevision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_lesson_id")
    private Lesson sourceLesson;

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

    @Column(name = "audio_url", length = 1024)
    private String audioUrl;

    @Column(name = "audio_public_id")
    private String audioPublicId;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(name = "xp_reward", nullable = false)
    private int xpReward;

    @Column(name = "is_placement_test", nullable = false)
    private boolean placementTest;

    @OneToMany(mappedBy = "lessonRevision", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<QuestionRevision> questions = new ArrayList<>();

    public Long getId() { return id; }
    public TopicRevision getTopicRevision() { return topicRevision; }
    public void setTopicRevision(TopicRevision topicRevision) { this.topicRevision = topicRevision; }
    public Lesson getSourceLesson() { return sourceLesson; }
    public void setSourceLesson(Lesson sourceLesson) { this.sourceLesson = sourceLesson; }
    public User getTeacher() { return teacher; }
    public void setTeacher(User teacher) { this.teacher = teacher; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public SkillType getSkillType() { return skillType; }
    public void setSkillType(SkillType skillType) { this.skillType = skillType; }
    public String getAudioUrl() { return audioUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
    public String getAudioPublicId() { return audioPublicId; }
    public void setAudioPublicId(String audioPublicId) { this.audioPublicId = audioPublicId; }
    public int getOrderIndex() { return orderIndex; }
    public void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }
    public int getXpReward() { return xpReward; }
    public void setXpReward(int xpReward) { this.xpReward = xpReward; }
    public boolean isPlacementTest() { return placementTest; }
    public void setPlacementTest(boolean placementTest) { this.placementTest = placementTest; }
    public List<QuestionRevision> getQuestions() { return questions; }
}
