package com.learne.backend.student.learning.dto;

import com.learne.backend.student.learning.entity.LessonProgress;
import com.learne.backend.teacher.content.entity.Topic;
import java.util.Comparator;
import java.util.List;

public record StudentProgressTopicResponse(
        Long id,
        String name,
        int orderIndex,
        List<StudentLessonProgressResponse> lessons
) {
    public static StudentProgressTopicResponse from(Topic topic, List<LessonProgress> progressItems) {
        return new StudentProgressTopicResponse(
                topic.getId(),
                topic.getName(),
                topic.getOrderIndex(),
                progressItems.stream()
                        .sorted(Comparator.comparingInt((LessonProgress progress) -> progress.getLesson().getOrderIndex())
                                .thenComparing(progress -> progress.getLesson().getId()))
                        .map(StudentLessonProgressResponse::from)
                        .toList()
        );
    }
}
