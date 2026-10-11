package com.learne.backend.teacher.content.enrichment;

import java.util.List;

public record CourseKnowledgePointRequest(CoursePayload course, List<QuestionPayload> questions) {
    public record CoursePayload(Long id, String name, String description, String type, String level) {}

    public record QuestionPayload(
            Long questionId,
            Long topicId,
            int topicOrderIndex,
            String topicName,
            String topicDescription,
            Long lessonId,
            int lessonOrderIndex,
            String lessonTitle,
            String lessonDescription,
            String lessonContent,
            String skillType,
            int questionOrderIndex,
            String questionType,
            String questionText,
            String correctAnswer,
            List<OptionPayload> options) {}

    public record OptionPayload(String optionText, boolean correct, int orderIndex) {}
}
