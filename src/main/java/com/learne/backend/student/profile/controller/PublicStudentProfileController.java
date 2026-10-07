package com.learne.backend.student.profile.controller;

import com.learne.backend.student.profile.dto.PublicStudentProfileResponse;
import com.learne.backend.student.profile.service.StudentProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/students")
class PublicStudentProfileController {
    private final StudentProfileService studentProfileService;

    PublicStudentProfileController(StudentProfileService studentProfileService) {
        this.studentProfileService = studentProfileService;
    }

    @GetMapping("/{studentId}/profile")
    PublicStudentProfileResponse getPublicProfile(@PathVariable Long studentId) {
        return studentProfileService.getPublicProfile(studentId);
    }
}
