package com.learne.backend.teacher.content.dto;

import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.QuestionType;
import java.util.Comparator;
import java.util.List;

public record QuestionResponse(
        Long id,
        QuestionType type,
        String questionText,
        String explanation,
        String correctAnswer,
        String knowledgePoint,
        int orderIndex,
        String difficulty,
        int xpReward,
        ContentStatus status,
        List<QuestionOptionResponse> options
) {
    public static QuestionResponse from(Question question) {
        return new QuestionResponse(
                question.getId(),
                question.getType(),
                question.getQuestionText(),
                question.getExplanation(),
                question.getCorrectAnswer(),
                question.getKnowledgePoint(),
                question.getOrderIndex(),
                question.getDifficulty(),
                question.getXpReward(),
                question.getStatus(),
                question.getOptions().stream()
                        .sorted(Comparator.comparingInt(QuestionOption::getOrderIndex).thenComparing(QuestionOption::getId))
                        .map(QuestionOptionResponse::from)
                        .toList()
        );
    }
}
