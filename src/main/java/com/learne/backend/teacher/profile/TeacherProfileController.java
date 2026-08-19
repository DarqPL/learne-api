package com.learne.backend.teacher.profile;

import com.learne.backend.domain.auth.User;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher/profile")
class TeacherProfileController {
    private final TeacherProfileService teacherProfileService;

    TeacherProfileController(TeacherProfileService teacherProfileService) {
        this.teacherProfileService = teacherProfileService;
    }

    @GetMapping
    TeacherProfileResponse getOwnProfile(Authentication authentication) {
        return teacherProfileService.getOwnProfile((User) authentication.getPrincipal());
    }
}
