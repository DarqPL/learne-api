package com.learne.backend.teacher.profile;

import com.learne.backend.domain.auth.User;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    @PostMapping(path = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    TeacherProfileResponse uploadAvatar(@RequestParam("file") MultipartFile file,
                                        Authentication authentication) {
        return teacherProfileService.updateAvatar((User) authentication.getPrincipal(), file);
    }

    @DeleteMapping("/avatar")
    TeacherProfileResponse deleteAvatar(Authentication authentication) {
        return teacherProfileService.deleteAvatar((User) authentication.getPrincipal());
    }

    @PostMapping(path = "/introduction-video", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    TeacherProfileResponse uploadIntroductionVideo(@RequestParam("file") MultipartFile file,
                                                   Authentication authentication) {
        return teacherProfileService.updateIntroductionVideo((User) authentication.getPrincipal(), file);
    }

    @DeleteMapping("/introduction-video")
    TeacherProfileResponse deleteIntroductionVideo(Authentication authentication) {
        return teacherProfileService.deleteIntroductionVideo((User) authentication.getPrincipal());
    }
}
