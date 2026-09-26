package com.learne.backend.admin.content.controller;

import com.learne.backend.admin.content.dto.AdminReviewCourseDetailResponse;
import com.learne.backend.admin.content.dto.AdminReviewCourseSummaryResponse;
import com.learne.backend.admin.content.dto.RejectContentRequest;
import com.learne.backend.admin.content.service.AdminContentReviewService;
import com.learne.backend.domain.auth.User;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/content")
class AdminContentReviewController {
    private final AdminContentReviewService adminContentReviewService;

    AdminContentReviewController(AdminContentReviewService adminContentReviewService) {
        this.adminContentReviewService = adminContentReviewService;
    }

    @GetMapping("/courses/pending")
    List<AdminReviewCourseSummaryResponse> listPendingCourses() {
        return adminContentReviewService.listPendingCourses();
    }

    @GetMapping("/courses/{courseId}")
    AdminReviewCourseDetailResponse getCourse(@PathVariable Long courseId) {
        return adminContentReviewService.getCourse(courseId);
    }

    @PostMapping("/courses/{courseId}/approve")
    AdminReviewCourseDetailResponse approveCourse(@PathVariable Long courseId, Authentication authentication) {
        return adminContentReviewService.approveCourse(currentUser(authentication), courseId);
    }

    @PostMapping("/courses/{courseId}/reject")
    AdminReviewCourseDetailResponse rejectCourse(@PathVariable Long courseId,
                                                  @Valid @RequestBody RejectContentRequest request,
                                                  Authentication authentication) {
        return adminContentReviewService.rejectCourse(currentUser(authentication), courseId, request.reason());
    }

    @PostMapping("/course-revisions/{revisionId}/approve")
    AdminReviewCourseDetailResponse approveRevision(@PathVariable Long revisionId, Authentication authentication) {
        return adminContentReviewService.approveRevision(currentUser(authentication), revisionId);
    }

    @PostMapping("/course-revisions/{revisionId}/reject")
    AdminReviewCourseDetailResponse rejectRevision(@PathVariable Long revisionId,
                                                   @Valid @RequestBody RejectContentRequest request,
                                                   Authentication authentication) {
        return adminContentReviewService.rejectRevision(currentUser(authentication), revisionId, request.reason());
    }

    private User currentUser(Authentication authentication) {
        return (User) authentication.getPrincipal();
    }
}
