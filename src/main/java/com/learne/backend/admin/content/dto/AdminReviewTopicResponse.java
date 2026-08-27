package com.learne.backend.admin.content.dto;

import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Topic;
import java.util.Comparator;
import java.util.List;

public record AdminReviewTopicResponse(
        Long id,
        String name,
        String description,
        int orderIndex,
        List<AdminReviewLessonResponse> lessons
) {
    public static AdminReviewTopicResponse from(Topic topic) {
        return new AdminReviewTopicResponse(
                topic.getId(),
                topic.getName(),
                topic.getDescription(),
                topic.getOrderIndex(),
                topic.getLessons().stream()
                        .sorted(Comparator.comparingInt(Lesson::getOrderIndex).thenComparing(Lesson::getId))
                        .map(AdminReviewLessonResponse::from)
                        .toList()
        );
    }
}
