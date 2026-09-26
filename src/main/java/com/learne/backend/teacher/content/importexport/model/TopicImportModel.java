package com.learne.backend.teacher.content.importexport.model;

import java.util.List;

public record TopicImportModel(Long topicId, int orderIndex, String title, String description,
        List<LessonImportModel> lessons) {
    public TopicImportModel {
        lessons = List.copyOf(lessons);
    }
}
