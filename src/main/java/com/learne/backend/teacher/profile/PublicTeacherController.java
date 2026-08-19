package com.learne.backend.teacher.profile;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teachers")
class PublicTeacherController {
    private final TeacherProfileService teacherProfileService;

    PublicTeacherController(TeacherProfileService teacherProfileService) {
        this.teacherProfileService = teacherProfileService;
    }

    @GetMapping
    List<TeacherProfileSummaryResponse> listPublicProfiles() {
        return teacherProfileService.listPublicProfiles();
    }

    @GetMapping("/{teacherId}")
    TeacherProfileResponse getPublicProfile(@PathVariable Long teacherId) {
        return teacherProfileService.getPublicProfile(teacherId);
    }
}
