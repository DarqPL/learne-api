package com.learne.backend.teacher.content.dto;

import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.SkillType;
import java.util.Comparator;
import java.util.List;

public record LessonResponse(
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
    public static LessonResponse from(Lesson lesson) {
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

    public static LessonResponse from(LessonRevision lesson) {
        return from(lesson, ContentStatus.DRAFT);
    }

    public static LessonResponse from(LessonRevision lesson, ContentStatus status) {
        return new LessonResponse(
                lesson.getId(),
                lesson.getTitle(),
                lesson.getDescription(),
                lesson.getContent(),
                lesson.getSkillType(),
                lesson.getOrderIndex(),
                lesson.getXpReward(),
                lesson.isPlacementTest(),
                status,
                null,
                lesson.getQuestions().stream()
                        .sorted(Comparator.comparingInt(QuestionRevision::getOrderIndex)
                                .thenComparing(QuestionRevision::getId))
                        .map(question -> QuestionResponse.from(question, status))
                        .toList()
        );
    }
}
