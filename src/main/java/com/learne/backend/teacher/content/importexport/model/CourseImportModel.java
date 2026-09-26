package com.learne.backend.teacher.content.importexport.model;

import java.util.List;

public record CourseImportModel(Long courseId, String name, String description, String type, String level,
        Integer orderIndex, List<TopicImportModel> topics) {
    public CourseImportModel {
        topics = List.copyOf(topics);
    }
}
