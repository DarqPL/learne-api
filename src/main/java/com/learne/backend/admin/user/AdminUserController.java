package com.learne.backend.admin.user;

import com.learne.backend.auth.session.dto.AuthUserResponse;
import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiMessageResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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

    AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
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
    List<AdminTeacherResponse> listTeachers() {
        return adminUserService.listTeachers();
    }
}
