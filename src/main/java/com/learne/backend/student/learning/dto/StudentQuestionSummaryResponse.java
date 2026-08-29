package com.learne.backend.student.learning.dto;

import com.learne.backend.teacher.content.entity.Question;

public record StudentQuestionSummaryResponse(
        Long id,
        String type,
        String questionText,
        String knowledgePoint,
        String difficulty,
        int xpReward,
        int optionCount
) {
    public static StudentQuestionSummaryResponse from(Question question) {
        return new StudentQuestionSummaryResponse(
                question.getId(),
                question.getType().name(),
                question.getQuestionText(),
                question.getKnowledgePoint(),
                question.getDifficulty(),
                question.getXpReward(),
                question.getOptions().size()
        );
    }
}
