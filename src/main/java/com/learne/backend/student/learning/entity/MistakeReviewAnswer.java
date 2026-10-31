package com.learne.backend.student.learning.entity;

import com.learne.backend.student.learning.enums.GradingMethod;
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
@Table(name = "mistake_review_answers")
public class MistakeReviewAnswer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_question_id", unique = true)
    private MistakeReviewSessionQuestion sessionQuestion;

    @Column(name = "answer_text", columnDefinition = "TEXT")
    private String answerText;

    @Column(name = "selected_option_id")
    private Long selectedOptionId;

    @Column(nullable = false)
    private boolean correct;

    @Column(name = "score_percent", nullable = false)
    private int scorePercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "grading_method", nullable = false)
    private GradingMethod gradingMethod;

    @Column(columnDefinition = "TEXT")
    private String feedback;

    @Column(name = "correct_answer_display", columnDefinition = "TEXT")
    private String correctAnswerDisplay;

    @Column(name = "explanation_snapshot", columnDefinition = "TEXT")
    private String explanationSnapshot;

    @Column(name = "answer_event_order", nullable = false)
    private Long answerEventOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() { if (createdAt == null) createdAt = Instant.now(); }

    public Long getId() { return id; }
    public MistakeReviewSessionQuestion getSessionQuestion() { return sessionQuestion; }
    public void setSessionQuestion(MistakeReviewSessionQuestion sessionQuestion) { this.sessionQuestion = sessionQuestion; }
    public String getAnswerText() { return answerText; }
    public void setAnswerText(String answerText) { this.answerText = answerText; }
    public Long getSelectedOptionId() { return selectedOptionId; }
    public void setSelectedOptionId(Long selectedOptionId) { this.selectedOptionId = selectedOptionId; }
    public boolean isCorrect() { return correct; }
    public void setCorrect(boolean correct) { this.correct = correct; }
    public int getScorePercent() { return scorePercent; }
    public void setScorePercent(int scorePercent) { this.scorePercent = scorePercent; }
    public GradingMethod getGradingMethod() { return gradingMethod; }
    public void setGradingMethod(GradingMethod gradingMethod) { this.gradingMethod = gradingMethod; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public String getCorrectAnswerDisplay() { return correctAnswerDisplay; }
    public void setCorrectAnswerDisplay(String correctAnswerDisplay) { this.correctAnswerDisplay = correctAnswerDisplay; }
    public String getExplanationSnapshot() { return explanationSnapshot; }
    public void setExplanationSnapshot(String explanationSnapshot) { this.explanationSnapshot = explanationSnapshot; }
    public Long getAnswerEventOrder() { return answerEventOrder; }
    public void setAnswerEventOrder(Long answerEventOrder) { this.answerEventOrder = answerEventOrder; }
    public Instant getCreatedAt() { return createdAt; }
}
