package com.learne.backend.monetization.dto;

import java.util.List;

public record LearningActivityReportResponse(
        String month,
        List<LearningActivityRow> rows) {
    public record LearningActivityRow(Long courseId, String courseName, Long teacherId, String teacherName,
                                      long completedLessons, long submittedQuizzes, long activityScore) { }
}
