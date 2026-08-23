package com.learne.backend.teacher.content.dto;

import com.learne.backend.teacher.content.entity.QuestionOption;

public record QuestionOptionResponse(
        Long id,
        String optionText,
        boolean correct,
        int orderIndex
) {
    public static QuestionOptionResponse from(QuestionOption option) {
        return new QuestionOptionResponse(option.getId(), option.getOptionText(), option.isCorrect(), option.getOrderIndex());
    }
}
