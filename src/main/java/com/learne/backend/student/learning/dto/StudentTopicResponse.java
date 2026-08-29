package com.learne.backend.student.learning.dto;

import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import java.util.Comparator;
import java.util.List;

public record StudentTopicResponse(
        Long id,
        String name,
        String description,
        int orderIndex,
        List<StudentLessonResponse> lessons
) {
    public static StudentTopicResponse from(Topic topic) {
        return new StudentTopicResponse(
                topic.getId(),
                topic.getName(),
                topic.getDescription(),
                topic.getOrderIndex(),
                topic.getLessons().stream()
                        .filter(lesson -> lesson.getStatus() == ContentStatus.APPROVED)
                        .sorted(Comparator.comparingInt(Lesson::getOrderIndex).thenComparing(Lesson::getId))
                        .map(StudentLessonResponse::from)
                        .toList()
        );
    }
}
