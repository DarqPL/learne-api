package com.learne.backend.student.learning.ai;

import java.util.List;

public record AiCourseRecommendationRequest(
        StudentPayload student,
        AssessmentSummaryPayload assessmentSummary,
        List<SkillStatPayload> skillStats,
        LearningHistoryPayload learningHistory,
        List<CandidateCoursePayload> candidateCourses,
        ConstraintPayload constraints) {
    public record StudentPayload(Long id, String level) {}

    public record AssessmentSummaryPayload(Long assessmentId, String level, Integer scorePercent, String completedAt) {}

    public record SkillStatPayload(String skill, Integer scorePercent, Integer attempts, List<String> weakKnowledgePoints) {}

    public record LearningHistoryPayload(
            List<Long> recentCourseIds,
            List<String> recentLessonTitles,
            List<String> weaknesses) {}

    public record CandidateCoursePayload(
            Long courseId,
            String title,
            String name,
            String type,
            String level,
            String description,
            String teacherName,
            boolean enrolled) {}

    public record ConstraintPayload(Integer maxItems, List<Long> allowedCourseIds) {}
}
