package com.learne.backend.monetization.dto;

import java.util.List;

public record TeacherShareReportResponse(
        String month,
        long planRevenuePoolVnd,
        long totalActivityScore,
        List<TeacherShareRow> rows) {
    public record TeacherShareRow(Long teacherId, String teacherName, long activityScore,
                                  long directCourseNetRevenueVnd, long suggestedPlanShareVnd,
                                  long suggestedTotalShareVnd) { }
}
