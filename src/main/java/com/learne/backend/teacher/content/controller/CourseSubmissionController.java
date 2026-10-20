package com.learne.backend.teacher.content.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.submission.*;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teacher/content/courses/{courseId}")
public class CourseSubmissionController {
    private final SubmissionQueryService service;
    public CourseSubmissionController(SubmissionQueryService service) { this.service=service; }
    @GetMapping("/submission")
    SubmissionResponse latest(Authentication auth,@PathVariable long courseId) { return service.get(((User)auth.getPrincipal()).getId(),courseId,null); }
    @GetMapping("/submissions/{submissionId}")
    SubmissionResponse exact(Authentication auth,@PathVariable long courseId,@PathVariable UUID submissionId) { return service.get(((User)auth.getPrincipal()).getId(),courseId,submissionId); }
}
