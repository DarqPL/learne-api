package com.learne.backend.student.learning.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.dto.StudentCourseDetailResponse;
import com.learne.backend.student.learning.dto.StudentCourseSummaryResponse;
import com.learne.backend.student.learning.dto.StudentEnrollmentProgressResponse;
import com.learne.backend.student.learning.dto.StudentEnrollmentResponse;
import com.learne.backend.student.learning.service.StudentLearningService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student")
class StudentLearningController {
    private final StudentLearningService studentLearningService;

    StudentLearningController(StudentLearningService studentLearningService) {
        this.studentLearningService = studentLearningService;
    }

    @GetMapping("/courses")
    List<StudentCourseSummaryResponse> listCourses(Authentication authentication,
                                                   @RequestParam(required = false) String level,
                                                   @RequestParam(required = false) Long teacherId) {
        return studentLearningService.listCourses(currentUser(authentication), level, teacherId);
    }

    @GetMapping("/courses/{courseId}")
    StudentCourseDetailResponse getCourse(@PathVariable Long courseId, Authentication authentication) {
        return studentLearningService.getCourse(currentUser(authentication), courseId);
    }

    @PostMapping("/courses/{courseId}/enroll")
    StudentEnrollmentResponse enroll(@PathVariable Long courseId, Authentication authentication) {
        return studentLearningService.enroll(currentUser(authentication), courseId);
    }

    @GetMapping("/enrollments")
    List<StudentEnrollmentResponse> listEnrollments(Authentication authentication) {
        return studentLearningService.listEnrollments(currentUser(authentication));
    }

    @GetMapping("/enrollments/{enrollmentId}")
    StudentEnrollmentProgressResponse getEnrollment(@PathVariable Long enrollmentId, Authentication authentication) {
        return studentLearningService.getEnrollmentProgress(currentUser(authentication), enrollmentId);
    }

    @GetMapping("/enrollments/{enrollmentId}/progress")
    StudentEnrollmentProgressResponse getEnrollmentProgress(@PathVariable Long enrollmentId, Authentication authentication) {
        return studentLearningService.getEnrollmentProgress(currentUser(authentication), enrollmentId);
    }

    @PostMapping("/enrollments/{enrollmentId}/lessons/{lessonId}/start")
    StudentEnrollmentProgressResponse startLesson(@PathVariable Long enrollmentId,
                                                  @PathVariable Long lessonId,
                                                  Authentication authentication) {
        return studentLearningService.startLesson(currentUser(authentication), enrollmentId, lessonId);
    }

    @PostMapping("/enrollments/{enrollmentId}/lessons/{lessonId}/complete")
    StudentEnrollmentProgressResponse completeLesson(@PathVariable Long enrollmentId,
                                                     @PathVariable Long lessonId,
                                                     Authentication authentication) {
        return studentLearningService.completeLesson(currentUser(authentication), enrollmentId, lessonId);
    }

    private User currentUser(Authentication authentication) {
        return (User) authentication.getPrincipal();
    }
}
