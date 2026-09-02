package com.learne.backend.student.learning.ai;

import java.util.List;

public record AiLearningPathRequest(
        StudentPayload student,
        ConstraintPayload constraints,
        LearningHistoryPayload learningHistory,
        List<CandidateLessonPayload> candidateLessons) {
    public record StudentPayload(Long id, String level) {}

    public record ConstraintPayload(Integer maxItems, List<Long> allowedLessonIds) {}

    public record LearningHistoryPayload(
            List<QuizAttemptPayload> recentQuizAttempts,
            List<QuestionAnswerPayload> questionAnswers) {}

    public record QuizAttemptPayload(
            Long lessonId,
            String lessonTitle,
            Integer scorePercent,
            boolean passed,
            String submittedAt) {}

    public record QuestionAnswerPayload(
            Long questionId,
            Long lessonId,
            String questionType,
            String prompt,
            String answerText,
            boolean correct,
            Integer scorePercent,
            String feedback) {}

    public record CandidateLessonPayload(
            Long lessonId,
            String lessonTitle,
            Long courseId,
            String courseTitle,
            Long teacherId,
            String level,
            String topicTitle,
            String summary) {}
}
