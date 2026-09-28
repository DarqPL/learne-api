package com.learne.backend.monetization.controller;

import com.learne.backend.monetization.dto.CourseSalesReportResponse;
import com.learne.backend.monetization.dto.LearningActivityReportResponse;
import com.learne.backend.monetization.dto.PlanSalesReportResponse;
import com.learne.backend.monetization.dto.TeacherShareReportResponse;
import com.learne.backend.monetization.service.AdminRevenueReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/revenue")
public class AdminRevenueReportController {
    private final AdminRevenueReportService reportService;

    public AdminRevenueReportController(AdminRevenueReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/course-sales")
    public CourseSalesReportResponse courseSales(@RequestParam String month) {
        return reportService.courseSales(month);
    }

    @GetMapping("/plan-sales")
    public PlanSalesReportResponse planSales(@RequestParam String month) {
        return reportService.planSales(month);
    }

    @GetMapping("/learning-activity")
    public LearningActivityReportResponse learningActivity(@RequestParam String month) {
        return reportService.learningActivity(month);
    }

    @GetMapping("/teacher-share")
    public TeacherShareReportResponse teacherShare(@RequestParam String month) {
        return reportService.teacherShare(month);
    }
}
