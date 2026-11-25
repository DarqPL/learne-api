package com.learne.backend.teacher.content.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.CourseCoverImageResponse;
import com.learne.backend.teacher.content.CourseCoverImageService;
import com.learne.backend.teacher.content.dto.TeacherCourseCoverStateResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/teacher/content")
class CourseCoverImageController {
    private final CourseCoverImageService service;

    CourseCoverImageController(CourseCoverImageService service) {
        this.service = service;
    }

    @GetMapping("/courses/{courseId}/cover-image")
    TeacherCourseCoverStateResponse state(@PathVariable Long courseId, Authentication authentication) {
        return service.state((User) authentication.getPrincipal(), courseId);
    }

    @PostMapping(value = "/courses/{courseId}/cover-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    CourseCoverImageResponse upload(@PathVariable Long courseId,
                                    @RequestParam("file") MultipartFile file,
                                    Authentication authentication) {
        return service.upload((User) authentication.getPrincipal(), courseId, file);
    }

    @DeleteMapping("/courses/{courseId}/cover-image")
    CourseCoverImageResponse remove(@PathVariable Long courseId, Authentication authentication) {
        return service.remove((User) authentication.getPrincipal(), courseId);
    }

    @DeleteMapping("/courses/{courseId}/cover-image/pending")
    CourseCoverImageResponse cancelPending(@PathVariable Long courseId, Authentication authentication) {
        return service.cancelPending((User) authentication.getPrincipal(), courseId);
    }

    @PostMapping(value = "/courses/{courseId}/cover-image/revision",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    CourseCoverImageResponse uploadRevision(@PathVariable Long courseId,
                                            @RequestParam("file") MultipartFile file,
                                            Authentication authentication) {
        return service.uploadRevision((User) authentication.getPrincipal(), courseId, file);
    }

    @DeleteMapping("/courses/{courseId}/cover-image/revision")
    CourseCoverImageResponse removeRevision(@PathVariable Long courseId, Authentication authentication) {
        return service.removeRevision((User) authentication.getPrincipal(), courseId);
    }
}
