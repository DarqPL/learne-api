package com.learne.backend.admin.content.controller;

import com.learne.backend.admin.content.dto.AdminCourseCoverReviewDetailResponse;
import com.learne.backend.admin.content.dto.AdminCourseCoverReviewSummaryResponse;
import com.learne.backend.admin.content.service.AdminCourseCoverReviewService;
import com.learne.backend.admin.content.dto.RejectContentRequest;
import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.pagination.CursorPageResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/content/course-covers")
class AdminCourseCoverReviewController {
    private final AdminCourseCoverReviewService reviews;

    AdminCourseCoverReviewController(AdminCourseCoverReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/pending")
    CursorPageResponse<AdminCourseCoverReviewSummaryResponse> listPending(
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor) {
        return reviews.listPending(size, cursor);
    }

    @GetMapping("/{reviewId}")
    AdminCourseCoverReviewDetailResponse getDetail(@PathVariable Long reviewId) {
        return reviews.getDetail(reviewId);
    }

    @PostMapping("/{reviewId}/approve")
    AdminCourseCoverReviewDetailResponse approve(
            @PathVariable Long reviewId, Authentication authentication) {
        return reviews.approve(currentUser(authentication), reviewId);
    }

    @PostMapping("/{reviewId}/reject")
    AdminCourseCoverReviewDetailResponse reject(
            @PathVariable Long reviewId,
            @Valid @RequestBody RejectContentRequest request,
            Authentication authentication) {
        return reviews.reject(currentUser(authentication), reviewId, request.reason());
    }

    private User currentUser(Authentication authentication) {
        return (User) authentication.getPrincipal();
    }
}
