package com.learne.backend.teacher.content.audio;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.teacher.content.dto.CourseRequest;
import com.learne.backend.teacher.content.dto.LessonRequest;
import com.learne.backend.teacher.content.dto.TopicRequest;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.enums.SkillType;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import com.learne.backend.teacher.content.service.TeacherContentService;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class LessonAudioTestFixture {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TeacherContentService teacherContentService;
    private final CourseRevisionRepository courseRevisionRepository;
    private final TransactionTemplate readTransaction;
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    LessonAudioTestFixture(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           TeacherContentService teacherContentService,
                           CourseRevisionRepository courseRevisionRepository,
                           PlatformTransactionManager transactionManager, MockMvc mockMvc) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.teacherContentService = teacherContentService;
        this.courseRevisionRepository = courseRevisionRepository;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.mockMvc = mockMvc;
    }

    Draft draft() throws Exception {
        User teacher = new User();
        teacher.setEmail("audio-teacher-" + UUID.randomUUID() + "@example.com");
        teacher.setFullName("Audio Teacher");
        teacher.setRole(UserRole.TEACHER);
        teacher.setPasswordHash(passwordEncoder.encode("Password123"));
        teacher.setActive(true);
        teacher.setLocked(false);
        teacher.setMustChangePassword(false);
        teacher = userRepository.save(teacher);

        long courseId = teacherContentService.createCourse(teacher,
                new CourseRequest("Audio course", "Fixture", CourseType.COMPREHENSIVE,
                        "A2", 1, CoursePricingType.FREE, null)).id();
        long topicId = teacherContentService.createTopic(teacher, courseId,
                new TopicRequest("Daily life", "Fixture", 1)).id();
        long liveLessonId = teacherContentService.createLesson(teacher, topicId,
                new LessonRequest("Morning routine", "Fixture", "I wake up early.",
                        SkillType.LISTENING, 1, 10, false)).id();

        User savedTeacher = teacher;
        String accessToken = loginAndGetAccessToken(teacher.getEmail());
        return readTransaction.execute(transaction -> {
            var revision = courseRevisionRepository.findTopByCourseIdOrderByRevisionNumberDesc(courseId)
                    .orElseThrow();
            var revisionLesson = revision.getTopics().stream()
                    .flatMap(topic -> topic.getLessons().stream())
                    .filter(lesson -> lesson.getSourceLesson() != null
                            && lesson.getSourceLesson().getId().equals(liveLessonId))
                    .findFirst()
                    .orElseThrow();
            return new Draft(savedTeacher, accessToken, courseId, liveLessonId,
                    revision.getId(), revisionLesson.getId());
        });
    }

    private String loginAndGetAccessToken(String email) throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "Password123"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    record Draft(User teacher, String accessToken, long courseId, long liveLessonId,
                 long revisionId, long revisionLessonId) {
    }
}
