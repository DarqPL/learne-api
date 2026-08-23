package com.learne.backend.teacher.content;

import java.util.Comparator;
import java.util.List;

record LessonResponse(
        Long id,
        String title,
        String description,
        String content,
        SkillType skillType,
        int orderIndex,
        int xpReward,
        boolean placementTest,
        ContentStatus status,
        String rejectionReason,
        List<QuestionResponse> questions
) {
    static LessonResponse from(Lesson lesson) {
        return new LessonResponse(
                lesson.getId(),
                lesson.getTitle(),
                lesson.getDescription(),
                lesson.getContent(),
                lesson.getSkillType(),
                lesson.getOrderIndex(),
                lesson.getXpReward(),
                lesson.isPlacementTest(),
                lesson.getStatus(),
                lesson.getRejectionReason(),
                lesson.getQuestions().stream()
                        .sorted(Comparator.comparingInt(Question::getOrderIndex).thenComparing(Question::getId))
                        .map(QuestionResponse::from)
                        .toList()
        );
    }
}
