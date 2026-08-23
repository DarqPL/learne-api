package com.learne.backend.teacher.content;

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
@Table(name = "question_options")
class QuestionOption {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id")
    private Question question;

    @Column(name = "option_text", nullable = false)
    private String optionText;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    Long getId() { return id; }
    Question getQuestion() { return question; }
    void setQuestion(Question question) { this.question = question; }
    String getOptionText() { return optionText; }
    void setOptionText(String optionText) { this.optionText = optionText; }
    boolean isCorrect() { return correct; }
    void setCorrect(boolean correct) { this.correct = correct; }
    int getOrderIndex() { return orderIndex; }
    void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }
}
