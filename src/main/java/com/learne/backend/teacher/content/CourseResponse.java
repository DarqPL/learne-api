package com.learne.backend.teacher.content;

import java.util.Comparator;
import java.util.List;

record CourseResponse(
        Long id,
        String name,
        String description,
        CourseType type,
        String level,
        int orderIndex,
        ContentStatus status,
        String rejectionReason,
        List<TopicResponse> topics
) {
    static CourseResponse from(Course course) {
        return new CourseResponse(
                course.getId(),
                course.getName(),
                course.getDescription(),
                course.getType(),
                course.getLevel(),
                course.getOrderIndex(),
                course.getStatus(),
                course.getRejectionReason(),
                course.getTopics().stream()
                        .sorted(Comparator.comparingInt(Topic::getOrderIndex).thenComparing(Topic::getId))
                        .map(TopicResponse::from)
                        .toList()
        );
    }
}
