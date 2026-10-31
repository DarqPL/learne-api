package com.learne.backend.student.learning.entity;

import com.learne.backend.teacher.content.entity.Question;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "mistake_review_session_questions")
public class MistakeReviewSessionQuestion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private MistakeReviewSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id")
    private Question question;

    @Column(name = "source_question_id_snapshot", nullable = false)
    private Long sourceQuestionIdSnapshot;

    @Column(name = "question_order", nullable = false)
    private int questionOrder;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Column(name = "question_type", nullable = false)
    private String questionType;

    @Column(name = "options_snapshot", nullable = false, columnDefinition = "TEXT")
    private String optionsSnapshot;

    @Column(name = "correct_answer_snapshot", columnDefinition = "TEXT")
    private String correctAnswerSnapshot;

    @Column(name = "explanation_snapshot", columnDefinition = "TEXT")
    private String explanationSnapshot;

    @OneToOne(mappedBy = "sessionQuestion", cascade = CascadeType.ALL, orphanRemoval = true)
    private MistakeReviewAnswer answer;

    public Long getId() { return id; }
    public MistakeReviewSession getSession() { return session; }
    public void setSession(MistakeReviewSession session) { this.session = session; }
    public Question getQuestion() { return question; }
    public void setQuestion(Question question) { this.question = question; }
    public Long getSourceQuestionIdSnapshot() { return sourceQuestionIdSnapshot; }
    public void setSourceQuestionIdSnapshot(Long sourceQuestionIdSnapshot) { this.sourceQuestionIdSnapshot = sourceQuestionIdSnapshot; }
    public int getQuestionOrder() { return questionOrder; }
    public void setQuestionOrder(int questionOrder) { this.questionOrder = questionOrder; }
    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }
    public String getQuestionType() { return questionType; }
    public void setQuestionType(String questionType) { this.questionType = questionType; }
    public String getOptionsSnapshot() { return optionsSnapshot; }
    public void setOptionsSnapshot(String optionsSnapshot) { this.optionsSnapshot = optionsSnapshot; }
    public String getCorrectAnswerSnapshot() { return correctAnswerSnapshot; }
    public void setCorrectAnswerSnapshot(String correctAnswerSnapshot) { this.correctAnswerSnapshot = correctAnswerSnapshot; }
    public String getExplanationSnapshot() { return explanationSnapshot; }
    public void setExplanationSnapshot(String explanationSnapshot) { this.explanationSnapshot = explanationSnapshot; }
    public MistakeReviewAnswer getAnswer() { return answer; }
    public void setAnswer(MistakeReviewAnswer answer) { this.answer = answer; }
}
