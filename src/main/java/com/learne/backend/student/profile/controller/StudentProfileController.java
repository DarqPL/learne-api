package com.learne.backend.student.profile.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.profile.dto.StudentProfileResponse;
import com.learne.backend.student.profile.dto.UpdateStudentProfileRequest;
import com.learne.backend.student.profile.service.StudentProfileService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student/profile")
class StudentProfileController {
    private final StudentProfileService studentProfileService;

    StudentProfileController(StudentProfileService studentProfileService) {
        this.studentProfileService = studentProfileService;
    }

    @GetMapping
    StudentProfileResponse getOwnProfile(Authentication authentication) {
        return studentProfileService.getOwnProfile((User) authentication.getPrincipal());
    }

    @PutMapping
    StudentProfileResponse updateOwnProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateStudentProfileRequest request) {
        return studentProfileService.updateOwnProfile((User) authentication.getPrincipal(), request);
    }
}
