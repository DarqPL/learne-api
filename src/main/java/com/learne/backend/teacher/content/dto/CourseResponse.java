package com.learne.backend.teacher.content.dto;

import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import java.util.Comparator;
import java.util.List;

public record CourseResponse(
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
    public static CourseResponse from(Course course) {
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
