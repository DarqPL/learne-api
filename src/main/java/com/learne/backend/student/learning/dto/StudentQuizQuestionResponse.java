package com.learne.backend.student.learning.dto;

import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;
import com.learne.backend.teacher.content.enums.QuestionType;
import java.util.Comparator;
import java.util.List;

public record StudentQuizQuestionResponse(
        Long id,
        String type,
        String questionText,
        String knowledgePoint,
        String difficulty,
        int xpReward,
        int orderIndex,
        String targetText,
        List<StudentQuizOptionResponse> options
) {
    public static StudentQuizQuestionResponse from(Question question) {
        String targetText = question.getType() == QuestionType.PRONUNCIATION ? question.getCorrectAnswer() : null;
        return new StudentQuizQuestionResponse(
                question.getId(),
                question.getType().name(),
                question.getQuestionText(),
                question.getKnowledgePoint(),
                question.getDifficulty(),
                question.getXpReward(),
                question.getOrderIndex(),
                targetText,
                question.getOptions().stream()
                        .sorted(Comparator.comparingInt(QuestionOption::getOrderIndex).thenComparing(QuestionOption::getId))
                        .map(StudentQuizOptionResponse::from)
                        .toList()
        );
    }
}
