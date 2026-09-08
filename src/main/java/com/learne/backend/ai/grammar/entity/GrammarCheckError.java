package com.learne.backend.ai.grammar.entity;

import com.learne.backend.ai.grammar.enums.GrammarCheckErrorType;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "grammar_check_errors")
public class GrammarCheckError {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_id")
    private GrammarCheck grammarCheck;

    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;

    @Column(name = "error_text", nullable = false, length = 500)
    private String errorText;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", nullable = false)
    private GrammarCheckErrorType errorType;

    @Column(nullable = false, length = 500)
    private String suggestion;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "start_pos", nullable = false)
    private int startPos;

    @Column(name = "end_pos", nullable = false)
    private int endPos;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public GrammarCheck getGrammarCheck() { return grammarCheck; }
    public void setGrammarCheck(GrammarCheck grammarCheck) { this.grammarCheck = grammarCheck; }
    public int getSequenceOrder() { return sequenceOrder; }
    public void setSequenceOrder(int sequenceOrder) { this.sequenceOrder = sequenceOrder; }
    public String getErrorText() { return errorText; }
    public void setErrorText(String errorText) { this.errorText = errorText; }
    public GrammarCheckErrorType getErrorType() { return errorType; }
    public void setErrorType(GrammarCheckErrorType errorType) { this.errorType = errorType; }
    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public int getStartPos() { return startPos; }
    public void setStartPos(int startPos) { this.startPos = startPos; }
    public int getEndPos() { return endPos; }
    public void setEndPos(int endPos) { this.endPos = endPos; }
    public Instant getCreatedAt() { return createdAt; }
}
