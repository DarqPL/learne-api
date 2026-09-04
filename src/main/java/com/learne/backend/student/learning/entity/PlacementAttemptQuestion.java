package com.learne.backend.student.learning.entity;

import com.learne.backend.teacher.content.entity.Question;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "placement_attempt_questions")
public class PlacementAttemptQuestion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "placement_attempt_id", nullable = false)
    private PlacementAttempt placementAttempt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;

    @Column(nullable = false, length = 50)
    private String skill;

    public Long getId() { return id; }
    public PlacementAttempt getPlacementAttempt() { return placementAttempt; }
    public void setPlacementAttempt(PlacementAttempt placementAttempt) { this.placementAttempt = placementAttempt; }
    public Question getQuestion() { return question; }
    public void setQuestion(Question question) { this.question = question; }
    public int getSequenceOrder() { return sequenceOrder; }
    public void setSequenceOrder(int sequenceOrder) { this.sequenceOrder = sequenceOrder; }
    public String getSkill() { return skill; }
    public void setSkill(String skill) { this.skill = skill; }
}
