package com.learne.backend.admin.content.dto;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;
import com.learne.backend.teacher.content.entity.QuestionOptionRevision;
import com.learne.backend.teacher.content.entity.QuestionRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.QuestionType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record AdminReviewQuestionResponse(
        Long id,
        QuestionType type,
        String questionText,
        String explanation,
        String correctAnswer,
        String knowledgePoint,
        int orderIndex,
        String difficulty,
        int xpReward,
        ContentStatus status,
        String rejectionReason,
        Long reviewedByAdminId,
        Instant reviewedAt,
        List<AdminReviewQuestionOptionResponse> options
) {
    public static AdminReviewQuestionResponse from(Question question) {
        User reviewedBy = question.getReviewedBy();

        return new AdminReviewQuestionResponse(
                question.getId(),
                question.getType(),
                question.getQuestionText(),
                question.getExplanation(),
                question.getCorrectAnswer(),
                question.getKnowledgePoint(),
                question.getOrderIndex(),
                question.getDifficulty(),
                question.getXpReward(),
                question.getStatus(),
                question.getRejectionReason(),
                reviewedBy == null ? null : reviewedBy.getId(),
                question.getReviewedAt(),
                question.getOptions().stream()
                        .sorted(Comparator.comparingInt(QuestionOption::getOrderIndex).thenComparing(QuestionOption::getId))
                        .map(AdminReviewQuestionOptionResponse::from)
                        .toList()
        );
    }

    public static AdminReviewQuestionResponse from(QuestionRevision question) {
        return new AdminReviewQuestionResponse(
                question.getId(),
                question.getType(),
                question.getQuestionText(),
                question.getExplanation(),
                question.getCorrectAnswer(),
                question.getKnowledgePoint(),
                question.getOrderIndex(),
                question.getDifficulty(),
                question.getXpReward(),
                ContentStatus.PENDING_REVIEW,
                null,
                null,
                null,
                question.getOptions().stream()
                        .sorted(Comparator.comparingInt(QuestionOptionRevision::getOrderIndex).thenComparing(QuestionOptionRevision::getId))
                        .map(AdminReviewQuestionOptionResponse::from)
                        .toList()
        );
    }
}
