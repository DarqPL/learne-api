package com.learne.backend.teacher.content;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiMessageResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher/content")
class TeacherContentController {
    private final TeacherContentService teacherContentService;

    TeacherContentController(TeacherContentService teacherContentService) {
        this.teacherContentService = teacherContentService;
    }

    @GetMapping("/courses")
    List<CourseResponse> listCourses(Authentication authentication) {
        return teacherContentService.listCourses(currentUser(authentication));
    }

    @PostMapping("/courses")
    CourseResponse createCourse(@Valid @RequestBody CourseRequest request, Authentication authentication) {
        return teacherContentService.createCourse(currentUser(authentication), request);
    }

    @GetMapping("/courses/{courseId}")
    CourseResponse getCourse(@PathVariable Long courseId, Authentication authentication) {
        return teacherContentService.getCourse(currentUser(authentication), courseId);
    }

    @PutMapping("/courses/{courseId}")
    CourseResponse updateCourse(@PathVariable Long courseId,
                                @Valid @RequestBody CourseRequest request,
                                Authentication authentication) {
        return teacherContentService.updateCourse(currentUser(authentication), courseId, request);
    }

    @DeleteMapping("/courses/{courseId}")
    ApiMessageResponse deleteCourse(@PathVariable Long courseId, Authentication authentication) {
        teacherContentService.deleteCourse(currentUser(authentication), courseId);
        return new ApiMessageResponse("Course deleted.");
    }

    @PostMapping("/courses/{courseId}/topics")
    TopicResponse createTopic(@PathVariable Long courseId,
                              @Valid @RequestBody TopicRequest request,
                              Authentication authentication) {
        return teacherContentService.createTopic(currentUser(authentication), courseId, request);
    }

    @PutMapping("/topics/{topicId}")
    TopicResponse updateTopic(@PathVariable Long topicId,
                              @Valid @RequestBody TopicRequest request,
                              Authentication authentication) {
        return teacherContentService.updateTopic(currentUser(authentication), topicId, request);
    }

    @DeleteMapping("/topics/{topicId}")
    ApiMessageResponse deleteTopic(@PathVariable Long topicId, Authentication authentication) {
        teacherContentService.deleteTopic(currentUser(authentication), topicId);
        return new ApiMessageResponse("Topic deleted.");
    }

    @PostMapping("/topics/{topicId}/lessons")
    LessonResponse createLesson(@PathVariable Long topicId,
                                @Valid @RequestBody LessonRequest request,
                                Authentication authentication) {
        return teacherContentService.createLesson(currentUser(authentication), topicId, request);
    }

    @PutMapping("/lessons/{lessonId}")
    LessonResponse updateLesson(@PathVariable Long lessonId,
                                @Valid @RequestBody LessonRequest request,
                                Authentication authentication) {
        return teacherContentService.updateLesson(currentUser(authentication), lessonId, request);
    }

    @DeleteMapping("/lessons/{lessonId}")
    ApiMessageResponse deleteLesson(@PathVariable Long lessonId, Authentication authentication) {
        teacherContentService.deleteLesson(currentUser(authentication), lessonId);
        return new ApiMessageResponse("Lesson deleted.");
    }

    @PostMapping("/lessons/{lessonId}/questions")
    QuestionResponse createQuestion(@PathVariable Long lessonId,
                                    @Valid @RequestBody QuestionRequest request,
                                    Authentication authentication) {
        return teacherContentService.createQuestion(currentUser(authentication), lessonId, request);
    }

    @PutMapping("/questions/{questionId}")
    QuestionResponse updateQuestion(@PathVariable Long questionId,
                                    @Valid @RequestBody QuestionRequest request,
                                    Authentication authentication) {
        return teacherContentService.updateQuestion(currentUser(authentication), questionId, request);
    }

    @DeleteMapping("/questions/{questionId}")
    ApiMessageResponse deleteQuestion(@PathVariable Long questionId, Authentication authentication) {
        teacherContentService.deleteQuestion(currentUser(authentication), questionId);
        return new ApiMessageResponse("Question deleted.");
    }

    @PostMapping("/courses/{courseId}/submit")
    CourseResponse submitCourse(@PathVariable Long courseId, Authentication authentication) {
        return teacherContentService.submitCourse(currentUser(authentication), courseId);
    }

    private User currentUser(Authentication authentication) {
        return (User) authentication.getPrincipal();
    }
}
