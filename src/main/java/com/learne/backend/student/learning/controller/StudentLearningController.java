package com.learne.backend.student.learning.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.dto.GenerateLearningPathRequest;
import com.learne.backend.student.learning.dto.NextLessonRecommendationResponse;
import com.learne.backend.student.learning.dto.SubmitQuizRequest;
import com.learne.backend.student.learning.dto.SubmitQuizResponse;
import com.learne.backend.student.learning.dto.StudentCourseDetailResponse;
import com.learne.backend.student.learning.dto.StudentCourseSummaryResponse;
import com.learne.backend.student.learning.dto.StudentEnrollmentProgressResponse;
import com.learne.backend.student.learning.dto.StudentEnrollmentResponse;
import com.learne.backend.student.learning.dto.StudentLearningPathResponse;
import com.learne.backend.student.learning.dto.StudentQuizAttemptResponse;
import com.learne.backend.student.learning.dto.StudentQuizResponse;
import com.learne.backend.student.learning.dto.StudentStatsResponse;
import com.learne.backend.student.learning.service.StudentLearningPathService;
import com.learne.backend.student.learning.service.StudentLearningService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student")
class StudentLearningController {
    private final StudentLearningService studentLearningService;
    private final StudentLearningPathService studentLearningPathService;

    StudentLearningController(StudentLearningService studentLearningService,
                              StudentLearningPathService studentLearningPathService) {
        this.studentLearningService = studentLearningService;
        this.studentLearningPathService = studentLearningPathService;
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

    @GetMapping("/stats")
    StudentStatsResponse getStats(Authentication authentication) {
        return studentLearningService.getStats(currentUser(authentication));
    }

    @GetMapping("/enrollments/{enrollmentId}/lessons/{lessonId}/quiz")
    StudentQuizResponse getLessonQuiz(@PathVariable Long enrollmentId,
                                       @PathVariable Long lessonId,
                                       Authentication authentication) {
        return studentLearningService.getLessonQuiz(currentUser(authentication), enrollmentId, lessonId);
    }

    @PostMapping("/enrollments/{enrollmentId}/lessons/{lessonId}/quiz/submit")
    SubmitQuizResponse submitLessonQuiz(@PathVariable Long enrollmentId,
                                         @PathVariable Long lessonId,
                                         @Valid @RequestBody SubmitQuizRequest request,
                                         Authentication authentication) {
        return studentLearningService.submitLessonQuiz(currentUser(authentication), enrollmentId, lessonId, request);
    }

    @GetMapping("/quiz-attempts/{attemptId}")
    StudentQuizAttemptResponse getQuizAttempt(@PathVariable Long attemptId, Authentication authentication) {
        return studentLearningService.getQuizAttempt(currentUser(authentication), attemptId);
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

    @PostMapping("/learning-path/generate")
    StudentLearningPathResponse generateLearningPath(
            @Valid @RequestBody(required = false) GenerateLearningPathRequest request,
            Authentication authentication) {
        GenerateLearningPathRequest effectiveRequest = request == null
                ? new GenerateLearningPathRequest(null, null)
                : request;
        return studentLearningPathService.generate(currentUser(authentication), effectiveRequest);
    }

    @GetMapping("/learning-path/current")
    StudentLearningPathResponse currentLearningPath(Authentication authentication) {
        return studentLearningPathService.current(currentUser(authentication));
    }

    @GetMapping("/recommendations/next-lesson")
    NextLessonRecommendationResponse nextLessonRecommendation(Authentication authentication) {
        return studentLearningPathService.nextLesson(currentUser(authentication));
    }

    private User currentUser(Authentication authentication) {
        return (User) authentication.getPrincipal();
    }
}
