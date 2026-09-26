package com.learne.backend.teacher.content.importexport.model;

import java.util.List;

public record QuestionImportModel(Long questionId, int orderIndex, String type, String questionText,
        String explanation, String correctAnswer, String knowledgePoint, String difficulty, Integer xpReward,
        List<OptionImportModel> options) {
    public QuestionImportModel {
        options = List.copyOf(options);
    }
}
