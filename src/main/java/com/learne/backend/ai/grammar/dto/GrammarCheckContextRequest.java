package com.learne.backend.ai.grammar.dto;

public record GrammarCheckContextRequest(Long courseId, Long lessonId, Long questionId, Long enrollmentId) {
    public boolean hasAnyValue() {
        return courseId != null || lessonId != null || questionId != null || enrollmentId != null;
    }
}
