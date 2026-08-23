package com.learne.backend.teacher.content;

record QuestionOptionResponse(
        Long id,
        String optionText,
        boolean correct,
        int orderIndex
) {
    static QuestionOptionResponse from(QuestionOption option) {
        return new QuestionOptionResponse(option.getId(), option.getOptionText(), option.isCorrect(), option.getOrderIndex());
    }
}
