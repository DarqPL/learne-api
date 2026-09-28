package com.learne.backend.monetization.dto;

import com.learne.backend.monetization.enums.PlanCode;
import java.util.List;

public record PlanSalesReportResponse(
        String month,
        List<PlanSalesRow> rows) {
    public record PlanSalesRow(PlanCode planCode, long paidOrderCount, long grossRevenueVnd,
                               long discountAmountVnd, long netRevenueVnd) { }
}
