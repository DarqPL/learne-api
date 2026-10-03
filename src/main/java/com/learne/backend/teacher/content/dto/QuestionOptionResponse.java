package com.learne.backend.teacher.content.dto;

import com.learne.backend.teacher.content.entity.QuestionOption;
import com.learne.backend.teacher.content.entity.QuestionOptionRevision;

public record QuestionOptionResponse(
        Long id,
        String optionText,
        boolean correct,
        int orderIndex
) {
    public static QuestionOptionResponse from(QuestionOption option) {
        return new QuestionOptionResponse(option.getId(), option.getOptionText(), option.isCorrect(), option.getOrderIndex());
    }

    public static QuestionOptionResponse from(QuestionOptionRevision option) {
        return new QuestionOptionResponse(
                option.getId(),
                option.getOptionText(),
                option.isCorrect(),
                option.getOrderIndex()
        );
    }
}
