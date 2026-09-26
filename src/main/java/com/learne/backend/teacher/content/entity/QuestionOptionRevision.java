package com.learne.backend.teacher.content.entity;

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
@Table(name = "question_option_revisions")
public class QuestionOptionRevision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_revision_id")
    private QuestionRevision questionRevision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_option_id")
    private QuestionOption sourceOption;

    @Column(name = "option_text", nullable = false, length = 1024)
    private String optionText;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    public Long getId() { return id; }
    public QuestionRevision getQuestionRevision() { return questionRevision; }
    public void setQuestionRevision(QuestionRevision questionRevision) { this.questionRevision = questionRevision; }
    public QuestionOption getSourceOption() { return sourceOption; }
    public void setSourceOption(QuestionOption sourceOption) { this.sourceOption = sourceOption; }
    public String getOptionText() { return optionText; }
    public void setOptionText(String optionText) { this.optionText = optionText; }
    public boolean isCorrect() { return correct; }
    public void setCorrect(boolean correct) { this.correct = correct; }
    public int getOrderIndex() { return orderIndex; }
    public void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }
}
