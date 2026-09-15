package com.learne.backend.student.learning.dto;

import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.enums.ContentStatus;
import java.util.Comparator;
import java.util.List;

public record StudentLessonResponse(
        Long id,
        String title,
        String description,
        String content,
        String audioUrl,
        String audioPublicId,
        String skillType,
        int orderIndex,
        int xpReward,
        long questionCount,
        List<StudentQuestionSummaryResponse> questions
) {
    public static StudentLessonResponse from(Lesson lesson) {
        List<StudentQuestionSummaryResponse> approvedQuestions = lesson.getQuestions().stream()
                .filter(question -> question.getStatus() == ContentStatus.APPROVED)
                .sorted(Comparator.comparingInt(Question::getOrderIndex).thenComparing(Question::getId))
                .map(StudentQuestionSummaryResponse::from)
                .toList();
        return new StudentLessonResponse(
                lesson.getId(),
                lesson.getTitle(),
                lesson.getDescription(),
                lesson.getContent(),
                lesson.getAudioUrl(),
                lesson.getAudioPublicId(),
                lesson.getSkillType().name(),
                lesson.getOrderIndex(),
                lesson.getXpReward(),
                approvedQuestions.size(),
                approvedQuestions
        );
    }
}
