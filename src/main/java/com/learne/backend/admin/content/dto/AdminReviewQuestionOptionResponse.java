package com.learne.backend.admin.content.dto;

import com.learne.backend.teacher.content.entity.QuestionOption;
import com.learne.backend.teacher.content.entity.QuestionOptionRevision;

public record AdminReviewQuestionOptionResponse(
        Long id,
        String optionText,
        boolean correct,
        int orderIndex
) {
    public static AdminReviewQuestionOptionResponse from(QuestionOption option) {
        return new AdminReviewQuestionOptionResponse(
                option.getId(),
                option.getOptionText(),
                option.isCorrect(),
                option.getOrderIndex()
        );
    }

    public static AdminReviewQuestionOptionResponse from(QuestionOptionRevision option) {
        return new AdminReviewQuestionOptionResponse(
                option.getId(),
                option.getOptionText(),
                option.isCorrect(),
                option.getOrderIndex()
        );
    }
}
