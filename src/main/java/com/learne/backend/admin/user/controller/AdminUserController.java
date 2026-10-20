package com.learne.backend.admin.user.controller;

import com.learne.backend.admin.user.dto.AdminStudentDetailResponse;
import com.learne.backend.admin.user.dto.AdminStudentSummaryResponse;
import com.learne.backend.admin.user.dto.AdminTeacherResponse;
import com.learne.backend.shared.pagination.NumberedPageResponse;
import com.learne.backend.admin.user.dto.CreateTeacherRequest;
import com.learne.backend.admin.user.service.AdminUserService;
import com.learne.backend.auth.session.dto.AuthUserResponse;
import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiMessageResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
class AdminUserController {

    private final AdminUserService adminUserService;
    private final com.learne.backend.admin.user.service.TeacherInvitationService invitations;

    AdminUserController(AdminUserService adminUserService, com.learne.backend.admin.user.service.TeacherInvitationService invitations) {
        this.adminUserService = adminUserService;
        this.invitations = invitations;
    }

    @PostMapping("/teachers/{teacherId}/invitation/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    com.learne.backend.admin.user.dto.InvitationDeliveryResponse resendInvitation(@PathVariable long teacherId) {
        return invitations.resend(teacherId);
    }

    @PostMapping("/users/{userId}/lock")
    ApiMessageResponse lockUser(@PathVariable Long userId, Authentication authentication) {
        adminUserService.lockUser(userId, (User) authentication.getPrincipal());
        return new ApiMessageResponse("User locked successfully.");
    }

    @PostMapping("/users/{userId}/unlock")
    ApiMessageResponse unlockUser(@PathVariable Long userId) {
        adminUserService.unlockUser(userId);
        return new ApiMessageResponse("User unlocked successfully.");
    }

    @PostMapping("/teachers")
    @ResponseStatus(HttpStatus.CREATED)
    AuthUserResponse createTeacher(@Valid @RequestBody CreateTeacherRequest request) {
        return AuthUserResponse.from(adminUserService.createTeacher(request));
    }

    @GetMapping("/teachers")
    NumberedPageResponse<AdminTeacherResponse> listTeachers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int normalizedPage = page == null ? 0 : Math.max(0, page);
        int normalizedSize = size == null ? 20 : Math.max(1, Math.min(size, 100));
        return adminUserService.listTeachers(normalizedPage, normalizedSize);
    }

    @GetMapping("/students")
    NumberedPageResponse<AdminStudentSummaryResponse> listStudents(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int normalizedPage = page == null ? 0 : Math.max(0, page);
        int normalizedSize = size == null ? 20 : Math.max(1, Math.min(size, 100));
        return adminUserService.listStudents(normalizedPage, normalizedSize);
    }

    @GetMapping("/students/{userId}")
    AdminStudentDetailResponse getStudent(@PathVariable Long userId) {
        return adminUserService.getStudent(userId);
    }
}
