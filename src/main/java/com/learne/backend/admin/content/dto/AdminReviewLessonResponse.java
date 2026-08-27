package com.learne.backend.admin.content.dto;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.SkillType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record AdminReviewLessonResponse(
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
        Long reviewedByAdminId,
        Instant reviewedAt,
        Instant publishedAt,
        List<AdminReviewQuestionResponse> questions
) {
    public static AdminReviewLessonResponse from(Lesson lesson) {
        User reviewedBy = lesson.getReviewedBy();

        return new AdminReviewLessonResponse(
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
                reviewedBy == null ? null : reviewedBy.getId(),
                lesson.getReviewedAt(),
                lesson.getPublishedAt(),
                lesson.getQuestions().stream()
                        .sorted(Comparator.comparingInt(Question::getOrderIndex).thenComparing(Question::getId))
                        .map(AdminReviewQuestionResponse::from)
                        .toList()
        );
    }
}
