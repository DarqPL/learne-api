package com.learne.backend.teacher.content.dto;

import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import java.util.Comparator;
import java.util.List;

public record TopicResponse(
        Long id,
        String name,
        String description,
        int orderIndex,
        List<LessonResponse> lessons
) {
    public static TopicResponse from(Topic topic) {
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

    public static TopicResponse from(TopicRevision topic) {
        return from(topic, ContentStatus.DRAFT);
    }

    public static TopicResponse from(TopicRevision topic, ContentStatus status) {
        return new TopicResponse(
                topic.getId(),
                topic.getName(),
                topic.getDescription(),
                topic.getOrderIndex(),
                topic.getLessons().stream()
                        .sorted(Comparator.comparingInt(LessonRevision::getOrderIndex)
                                .thenComparing(LessonRevision::getId))
                        .map(lesson -> LessonResponse.from(lesson, status))
                        .toList()
        );
    }
}
