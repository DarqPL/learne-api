package com.learne.backend.teacher.content.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiMessageResponse;
import com.learne.backend.teacher.content.dto.CourseRequest;
import com.learne.backend.teacher.content.dto.CourseResponse;
import com.learne.backend.shared.pagination.CursorPageResponse;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.dto.LessonRequest;
import com.learne.backend.teacher.content.dto.LessonResponse;
import com.learne.backend.teacher.content.dto.QuestionRequest;
import com.learne.backend.teacher.content.dto.QuestionResponse;
import com.learne.backend.teacher.content.dto.TopicRequest;
import com.learne.backend.teacher.content.dto.TopicResponse;
import com.learne.backend.teacher.content.importexport.dto.CourseImportResponse;
import com.learne.backend.teacher.content.importexport.service.CourseImportExportService;
import com.learne.backend.teacher.content.importexport.template.CourseExcelTemplateExporter;
import com.learne.backend.teacher.content.service.TeacherContentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/teacher/content")
class TeacherContentController {
    private final TeacherContentService teacherContentService;
    private final CourseExcelTemplateExporter courseExcelTemplateExporter;
    private final CourseImportExportService courseImportExportService;

    TeacherContentController(TeacherContentService teacherContentService,
                             CourseExcelTemplateExporter courseExcelTemplateExporter,
                             CourseImportExportService courseImportExportService) {
        this.teacherContentService = teacherContentService;
        this.courseExcelTemplateExporter = courseExcelTemplateExporter;
        this.courseImportExportService = courseImportExportService;
    }

    @GetMapping(value = "/course-template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ResponseEntity<byte[]> downloadCourseTemplate(Authentication authentication) {
        currentUser(authentication);
        byte[] workbook = courseExcelTemplateExporter.blankTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=course-template.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(workbook);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    CourseImportResponse importCourse(@RequestParam("file") MultipartFile file, Authentication authentication) {
        return courseImportExportService.importCourse(currentUser(authentication), file);
    }

    @GetMapping(value = "/courses/{courseId}/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ResponseEntity<byte[]> exportCourse(@PathVariable Long courseId, Authentication authentication) {
        byte[] workbook = courseImportExportService.exportCourse(currentUser(authentication), courseId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=course-" + courseId + ".xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(workbook);
    }

    @GetMapping("/courses")
    CursorPageResponse<CourseResponse> listCourses(
            Authentication authentication,
            @RequestParam(required = false) ContentStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        return teacherContentService.listCourses(
                currentUser(authentication), status, q, boundedCursorSize(size), cursor);
    }

    private static int boundedCursorSize(Integer size) {
        if (size == null) {
            return 20;
        }
        return Math.max(1, Math.min(size, 50));
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

    @PostMapping("/courses/{courseId}/edit-revision")
    CourseResponse createEditRevision(@PathVariable Long courseId, Authentication authentication) {
        return teacherContentService.createEditRevision(currentUser(authentication), courseId);
    }

    private User currentUser(Authentication authentication) {
        return (User) authentication.getPrincipal();
    }
}
