package com.learne.backend.student.learning.entity;

import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;
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
@Table(name = "placement_attempt_answers")
public class PlacementAttemptAnswer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "placement_attempt_id", nullable = false)
    private PlacementAttempt placementAttempt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "answer_text", columnDefinition = "TEXT")
    private String answerText;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_option_id")
    private QuestionOption selectedOption;

    @Column(name = "selected_option_ids_json", columnDefinition = "TEXT")
    private String selectedOptionIdsJson;

    @Column(nullable = false)
    private boolean correct;

    @Column(name = "score_percent", nullable = false)
    private int scorePercent;

    @Column(columnDefinition = "TEXT")
    private String feedback;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public PlacementAttempt getPlacementAttempt() { return placementAttempt; }
    public void setPlacementAttempt(PlacementAttempt placementAttempt) { this.placementAttempt = placementAttempt; }
    public Question getQuestion() { return question; }
    public void setQuestion(Question question) { this.question = question; }
    public String getAnswerText() { return answerText; }
    public void setAnswerText(String answerText) { this.answerText = answerText; }
    public QuestionOption getSelectedOption() { return selectedOption; }
    public void setSelectedOption(QuestionOption selectedOption) { this.selectedOption = selectedOption; }
    public String getSelectedOptionIdsJson() { return selectedOptionIdsJson; }
    public void setSelectedOptionIdsJson(String selectedOptionIdsJson) { this.selectedOptionIdsJson = selectedOptionIdsJson; }
    public boolean isCorrect() { return correct; }
    public void setCorrect(boolean correct) { this.correct = correct; }
    public int getScorePercent() { return scorePercent; }
    public void setScorePercent(int scorePercent) { this.scorePercent = scorePercent; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public Instant getCreatedAt() { return createdAt; }
}
