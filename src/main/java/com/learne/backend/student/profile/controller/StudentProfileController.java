package com.learne.backend.student.profile.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.profile.dto.StudentProfileResponse;
import com.learne.backend.student.profile.dto.UpdateStudentProfileRequest;
import com.learne.backend.student.profile.service.StudentProfileService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    @PostMapping(path = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    StudentProfileResponse uploadAvatar(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        return studentProfileService.updateAvatar((User) authentication.getPrincipal(), file);
    }

    @DeleteMapping("/avatar")
    StudentProfileResponse deleteAvatar(Authentication authentication) {
        return studentProfileService.deleteAvatar((User) authentication.getPrincipal());
    }
}
