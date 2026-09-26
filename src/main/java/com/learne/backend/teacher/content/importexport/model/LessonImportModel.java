package com.learne.backend.teacher.content.importexport.model;

import java.util.List;

public record LessonImportModel(Long lessonId, int orderIndex, String title, String description, String content,
        String skillType, Integer xpReward, boolean placementTest, String audioFile,
        List<QuestionImportModel> questions) {
    public LessonImportModel {
        questions = List.copyOf(questions);
    }
}
