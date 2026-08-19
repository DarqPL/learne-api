package com.learne.backend.teacher.profile;

import com.learne.backend.domain.auth.User;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PutMapping
    TeacherProfileResponse updateOwnProfile(@Valid @RequestBody UpdateTeacherProfileRequest request,
                                            Authentication authentication) {
        return teacherProfileService.updateOwnProfile((User) authentication.getPrincipal(), request);
    }
}
