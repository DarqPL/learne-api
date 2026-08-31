package com.learne.backend.student.learning.dto;

import com.learne.backend.teacher.content.entity.QuestionOption;

public record StudentQuizOptionResponse(Long id, String optionText, int orderIndex) {
    public static StudentQuizOptionResponse from(QuestionOption option) {
        return new StudentQuizOptionResponse(option.getId(), option.getOptionText(), option.getOrderIndex());
    }
}
