package com.learne.backend.monetization.dto;

import java.util.List;

public record CourseSalesReportResponse(
        String month,
        List<CourseSalesRow> rows) {
    public record CourseSalesRow(Long courseId, String courseName, Long teacherId, String teacherName,
                                 long paidOrderCount, long grossRevenueVnd, long discountAmountVnd,
                                 long netRevenueVnd) { }
}
