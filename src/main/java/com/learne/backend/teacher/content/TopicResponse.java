package com.learne.backend.teacher.content;

import java.util.Comparator;
import java.util.List;

record TopicResponse(
        Long id,
        String name,
        String description,
        int orderIndex,
        List<LessonResponse> lessons
) {
    static TopicResponse from(Topic topic) {
        return new TopicResponse(
                topic.getId(),
                topic.getName(),
                topic.getDescription(),
                topic.getOrderIndex(),
                topic.getLessons().stream()
                        .sorted(Comparator.comparingInt(Lesson::getOrderIndex).thenComparing(Lesson::getId))
                        .map(LessonResponse::from)
                        .toList()
        );
    }
}
