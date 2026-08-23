package com.learne.backend.teacher.content.dto;

import com.learne.backend.teacher.content.enums.QuestionType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record QuestionRequest(
        @NotNull QuestionType type,
        @NotBlank String questionText,
        String explanation,
        @Size(max = 1024) String correctAnswer,
        @Size(max = 255) String knowledgePoint,
        Integer orderIndex,
        @Size(max = 50) String difficulty,
        @Min(0) Integer xpReward,
        List<@jakarta.validation.Valid QuestionOptionRequest> options
) {
    public QuestionRequest {
        if (questionText != null) questionText = questionText.trim();
        if (explanation != null) explanation = explanation.trim();
        if (correctAnswer != null) correctAnswer = correctAnswer.trim();
        if (knowledgePoint != null) knowledgePoint = knowledgePoint.trim();
        if (difficulty != null) difficulty = difficulty.trim();
    }
}
