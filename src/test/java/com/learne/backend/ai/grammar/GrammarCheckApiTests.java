package com.learne.backend.ai.grammar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.ai.grammar.ai.AiGrammarCheckClient;
import com.learne.backend.ai.grammar.ai.AiGrammarCheckRequest;
import com.learne.backend.ai.grammar.ai.AiGrammarCheckResponse;
import com.learne.backend.ai.grammar.entity.GrammarCheck;
import com.learne.backend.ai.grammar.enums.GrammarCheckStatus;
import com.learne.backend.ai.grammar.repository.GrammarCheckRepository;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.enums.EnrollmentStatus;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.enums.SkillType;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class GrammarCheckApiTests {
    @Autowired private EntityManager entityManager;
    @Autowired private GrammarCheckRepository grammarCheckRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private CourseEnrollmentRepository courseEnrollmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    @MockitoBean private AiGrammarCheckClient aiGrammarCheckClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void flywayCreatesGrammarCheckTables() {
        assertThat(tableExists("GRAMMAR_CHECKS")).isTrue();
        assertThat(tableExists("GRAMMAR_CHECK_ERRORS")).isTrue();
    }

    @Test
    void studentSubmitsGrammarCheck() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        mockSuccessfulGrammarCheck();

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.inputText").value("She are happy"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].errorText").value("are"))
                .andExpect(jsonPath("$.errors[0].suggestion").value("is"));
    }

    @Test
    void studentSubmitsGrammarCheckWithPaddedTwoThousandCharacterInput() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        when(aiGrammarCheckClient.checkGrammar(any(AiGrammarCheckRequest.class)))
                .thenReturn(new AiGrammarCheckResponse(List.of()));
        String inputText = " " + "x".repeat(2000) + " ";

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InputOnlyRequest(inputText))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.inputText").value("x".repeat(2000)));
    }

    @Test
    void teacherSubmitsGrammarCheckWithoutContext() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        mockSuccessfulGrammarCheck();

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(teacher.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void teacherGrammarCheckRejectsLearningContext() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(teacher.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "courseId": 1 }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Teacher grammar checks cannot include learning context"));
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentSubmitsGrammarCheckWithValidLearningContext() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Grammar Basics");
        Lesson lesson = saveLesson(teacher, saveTopic(course), "Subject Verb Agreement");
        Question question = saveQuestion(teacher, lesson, "Choose the correct verb");
        CourseEnrollment enrollment = saveEnrollment(student, course);
        mockSuccessfulGrammarCheck();

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": {
                                    "courseId": %d,
                                    "lessonId": %d,
                                    "questionId": %d,
                                    "enrollmentId": %d
                                  }
                                }
                                """.formatted(course.getId(), lesson.getId(), question.getId(), enrollment.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.courseId").value(course.getId()))
                .andExpect(jsonPath("$.lessonId").value(lesson.getId()))
                .andExpect(jsonPath("$.questionId").value(question.getId()))
                .andExpect(jsonPath("$.enrollmentId").value(enrollment.getId()));

        GrammarCheck persisted = grammarCheckRepository.findAll().getFirst();
        assertThat(persisted.getCourse().getId()).isEqualTo(course.getId());
        assertThat(persisted.getLesson().getId()).isEqualTo(lesson.getId());
        assertThat(persisted.getQuestion().getId()).isEqualTo(question.getId());
        assertThat(persisted.getEnrollment().getId()).isEqualTo(enrollment.getId());
    }

    @Test
    void studentGrammarCheckRejectsUnenrolledCourseWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Grammar Basics");

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "courseId": %d }
                                }
                                """.formatted(course.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsNonexistentCourseWithoutCallingAi() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "courseId": 999999 }
                                }
                                """))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsCourseWithCancelledEnrollmentWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Grammar Basics");
        saveEnrollment(student, course, EnrollmentStatus.CANCELLED);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "courseId": %d }
                                }
                                """.formatted(course.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsUnapprovedCourseWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Draft Grammar Basics", ContentStatus.DRAFT);
        saveEnrollment(student, course);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "courseId": %d }
                                }
                                """.formatted(course.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsUnenrolledLessonWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Grammar Basics");
        Lesson lesson = saveLesson(teacher, saveTopic(course), "Subject Verb Agreement");

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "lessonId": %d }
                                }
                                """.formatted(lesson.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsNonexistentLessonWithoutCallingAi() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "lessonId": 999999 }
                                }
                                """))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsLessonWithCancelledEnrollmentWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Grammar Basics");
        Lesson lesson = saveLesson(teacher, saveTopic(course), "Subject Verb Agreement");
        saveEnrollment(student, course, EnrollmentStatus.CANCELLED);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "lessonId": %d }
                                }
                                """.formatted(lesson.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsLessonWithUnapprovedCourseWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Draft Grammar Basics", ContentStatus.DRAFT);
        Lesson lesson = saveLesson(teacher, saveTopic(course), "Subject Verb Agreement");
        saveEnrollment(student, course);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "lessonId": %d }
                                }
                                """.formatted(lesson.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsUnenrolledQuestionWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Grammar Basics");
        Lesson lesson = saveLesson(teacher, saveTopic(course), "Subject Verb Agreement");
        Question question = saveQuestion(teacher, lesson, "Choose the correct verb");

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "questionId": %d }
                                }
                                """.formatted(question.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsNonexistentQuestionWithoutCallingAi() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "questionId": 999999 }
                                }
                                """))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsQuestionWithCancelledEnrollmentWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Grammar Basics");
        Lesson lesson = saveLesson(teacher, saveTopic(course), "Subject Verb Agreement");
        Question question = saveQuestion(teacher, lesson, "Choose the correct verb");
        saveEnrollment(student, course, EnrollmentStatus.CANCELLED);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "questionId": %d }
                                }
                                """.formatted(question.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsQuestionWithUnapprovedCourseWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Draft Grammar Basics", ContentStatus.DRAFT);
        Lesson lesson = saveLesson(teacher, saveTopic(course), "Subject Verb Agreement");
        Question question = saveQuestion(teacher, lesson, "Choose the correct verb");
        saveEnrollment(student, course);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "questionId": %d }
                                }
                                """.formatted(question.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsUnapprovedLessonWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Grammar Basics");
        Lesson lesson = saveLesson(teacher, saveTopic(course), "Draft Lesson", ContentStatus.DRAFT);
        saveEnrollment(student, course);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "lessonId": %d }
                                }
                                """.formatted(lesson.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsUnapprovedQuestionWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Grammar Basics");
        Lesson lesson = saveLesson(teacher, saveTopic(course), "Subject Verb Agreement");
        Question question = saveQuestion(teacher, lesson, "Choose the correct verb", ContentStatus.DRAFT);
        saveEnrollment(student, course);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "questionId": %d }
                                }
                                """.formatted(question.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentSubmitsGrammarCheckWithQuestionOnlyLearningContext() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Grammar Basics");
        Lesson lesson = saveLesson(teacher, saveTopic(course), "Subject Verb Agreement");
        Question question = saveQuestion(teacher, lesson, "Choose the correct verb");
        saveEnrollment(student, course);
        mockSuccessfulGrammarCheck();

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "questionId": %d }
                                }
                                """.formatted(question.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.courseId").value(course.getId()))
                .andExpect(jsonPath("$.lessonId").value(lesson.getId()))
                .andExpect(jsonPath("$.questionId").value(question.getId()))
                .andExpect(jsonPath("$.enrollmentId").doesNotExist());

        GrammarCheck persisted = grammarCheckRepository.findAll().getFirst();
        assertThat(persisted.getCourse().getId()).isEqualTo(course.getId());
        assertThat(persisted.getLesson().getId()).isEqualTo(lesson.getId());
        assertThat(persisted.getQuestion().getId()).isEqualTo(question.getId());
        assertThat(persisted.getEnrollment()).isNull();
    }

    @Test
    void studentGrammarCheckRejectsEnrollmentCourseMismatchWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course enrolledCourse = saveCourse(teacher, "Grammar Basics");
        Course otherCourse = saveCourse(teacher, "Writing Basics");
        CourseEnrollment enrollment = saveEnrollment(student, enrolledCourse);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "courseId": %d, "enrollmentId": %d }
                                }
                                """.formatted(otherCourse.getId(), enrollment.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsAnotherStudentsEnrollmentWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        User otherStudent = saveUser("other-student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Grammar Basics");
        CourseEnrollment otherEnrollment = saveEnrollment(otherStudent, course);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "enrollmentId": %d }
                                }
                                """.formatted(otherEnrollment.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsNonexistentEnrollmentWithoutCallingAi() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "enrollmentId": 999999 }
                                }
                                """))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsCancelledEnrollmentWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Grammar Basics");
        CourseEnrollment enrollment = saveEnrollment(student, course, EnrollmentStatus.CANCELLED);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "enrollmentId": %d }
                                }
                                """.formatted(enrollment.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsLessonCourseMismatchWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course lessonCourse = saveCourse(teacher, "Grammar Basics");
        Course otherCourse = saveCourse(teacher, "Writing Basics");
        Lesson lesson = saveLesson(teacher, saveTopic(lessonCourse), "Subject Verb Agreement");

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "courseId": %d, "lessonId": %d }
                                }
                                """.formatted(otherCourse.getId(), lesson.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void studentGrammarCheckRejectsQuestionLessonMismatchWithoutCallingAi() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        Course course = saveCourse(teacher, "Grammar Basics");
        Topic topic = saveTopic(course);
        Lesson lesson = saveLesson(teacher, topic, "Subject Verb Agreement");
        Lesson otherLesson = saveLesson(teacher, topic, "Pronouns");
        Question question = saveQuestion(teacher, lesson, "Choose the correct verb");

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy",
                                  "context": { "lessonId": %d, "questionId": %d }
                                }
                                """.formatted(otherLesson.getId(), question.getId())))
                .andExpect(status().isBadRequest());
        verify(aiGrammarCheckClient, never()).checkGrammar(any(AiGrammarCheckRequest.class));
    }

    @Test
    void grammarCheckRejectsBlankInput() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "   "
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void grammarCheckRejectsLongInput() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String longInput = "a".repeat(2001);

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InputOnlyRequest(longInput))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aiFailureReturnsFailedCheckId() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        when(aiGrammarCheckClient.checkGrammar(any(AiGrammarCheckRequest.class)))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "AI unavailable"));

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy"
                                }
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.checkId").isNumber())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("AI unavailable"));
    }

    @Test
    void aiContractFailureReturnsFailedCheckId() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        when(aiGrammarCheckClient.checkGrammar(any(AiGrammarCheckRequest.class)))
                .thenReturn(new AiGrammarCheckResponse(null));

        mockMvc.perform(post("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputText": "She are happy"
                                }
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.checkId").isNumber())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("AI returned invalid response shape"));

        GrammarCheck persisted = grammarCheckRepository.findAll().getFirst();
        assertThat(persisted.getStatus()).isEqualTo(GrammarCheckStatus.FAILED);
    }

    @Test
    void historyReturnsCurrentUserGrammarChecksOnly() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        User otherStudent = saveUser("other-student@example.com", UserRole.STUDENT);
        saveGrammarCheck(student, "My visible text", GrammarCheckStatus.SUCCEEDED);
        saveGrammarCheck(otherStudent, "Other hidden text", GrammarCheckStatus.SUCCEEDED);

        mockMvc.perform(get("/api/grammar-checks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].inputPreview").value("My visible text"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void historyClampsZeroPageSizeToOne() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        saveGrammarCheck(student, "First visible text", GrammarCheckStatus.SUCCEEDED);
        saveGrammarCheck(student, "Second visible text", GrammarCheckStatus.SUCCEEDED);

        mockMvc.perform(get("/api/grammar-checks")
                        .param("size", "0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void historyClampsLargePageSizeToMaximum() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        for (int i = 0; i < 51; i++) {
            saveGrammarCheck(student, "Visible text " + i, GrammarCheckStatus.SUCCEEDED);
        }

        mockMvc.perform(get("/api/grammar-checks")
                        .param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(50)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(51))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void historyClampsNegativePageToFirstPage() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        saveGrammarCheck(student, "My visible text", GrammarCheckStatus.SUCCEEDED);

        mockMvc.perform(get("/api/grammar-checks")
                        .param("page", "-1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].inputPreview").value("My visible text"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void detailRejectsGrammarCheckOwnedByAnotherUser() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        User otherStudent = saveUser("other-student@example.com", UserRole.STUDENT);
        GrammarCheck otherCheck = saveGrammarCheck(otherStudent, "Other hidden text", GrammarCheckStatus.SUCCEEDED);

        mockMvc.perform(get("/api/grammar-checks/{id}", otherCheck.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail())))
                .andExpect(status().isNotFound());
    }

    @Test
    void detailReturnsCurrentUserGrammarCheck() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        GrammarCheck check = saveGrammarCheck(student, "My detailed text", GrammarCheckStatus.SUCCEEDED);

        mockMvc.perform(get("/api/grammar-checks/{id}", check.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(check.getId()))
                .andExpect(jsonPath("$.inputText").value("My detailed text"));
    }

    private boolean tableExists(String tableName) {
        Number count = (Number) entityManager.createNativeQuery("""
                select count(*)
                from information_schema.tables
                where upper(table_name) = :tableName
                """)
                .setParameter("tableName", tableName)
                .getSingleResult();
        return count.intValue() > 0;
    }

    private void mockSuccessfulGrammarCheck() {
        when(aiGrammarCheckClient.checkGrammar(any(AiGrammarCheckRequest.class)))
                .thenReturn(new AiGrammarCheckResponse(List.of(new AiGrammarCheckResponse.ErrorPayload(
                        "are", "GRAMMAR", "is", "Use singular verb agreement.", 4, 7))));
    }

    private User saveUser(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(role.name() + " User");
        user.setPasswordHash(passwordEncoder.encode("Password123"));
        user.setRole(role);
        user.setActive(true);
        user.setLocked(false);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }

    private GrammarCheck saveGrammarCheck(User user, String inputText, GrammarCheckStatus status) {
        GrammarCheck check = new GrammarCheck();
        check.setUser(user);
        check.setStatus(status);
        check.setInputText(inputText);
        check.setCompletedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return grammarCheckRepository.save(check);
    }

    private Course saveCourse(User teacher, String name) {
        return saveCourse(teacher, name, ContentStatus.APPROVED);
    }

    private Course saveCourse(User teacher, String name, ContentStatus status) {
        Course course = new Course();
        course.setTeacher(teacher);
        course.setName(name);
        course.setDescription(name + " description");
        course.setType(CourseType.GRAMMAR);
        course.setLevel("A1");
        course.setOrderIndex(1);
        course.setStatus(status);
        return courseRepository.saveAndFlush(course);
    }

    private Topic saveTopic(Course course) {
        Topic topic = new Topic();
        topic.setCourse(course);
        topic.setName(course.getName() + " Topic");
        topic.setDescription("Topic description");
        topic.setOrderIndex(1);
        return topicRepository.saveAndFlush(topic);
    }

    private Lesson saveLesson(User teacher, Topic topic, String title) {
        return saveLesson(teacher, topic, title, ContentStatus.APPROVED);
    }

    private Lesson saveLesson(User teacher, Topic topic, String title, ContentStatus status) {
        Lesson lesson = new Lesson();
        lesson.setTeacher(teacher);
        lesson.setTopic(topic);
        lesson.setTitle(title);
        lesson.setDescription(title + " description");
        lesson.setContent(title + " content");
        lesson.setSkillType(SkillType.GRAMMAR);
        lesson.setOrderIndex(1);
        lesson.setXpReward(10);
        lesson.setStatus(status);
        return lessonRepository.saveAndFlush(lesson);
    }

    private Question saveQuestion(User teacher, Lesson lesson, String text) {
        return saveQuestion(teacher, lesson, text, ContentStatus.APPROVED);
    }

    private Question saveQuestion(User teacher, Lesson lesson, String text, ContentStatus status) {
        Question question = new Question();
        question.setTeacher(teacher);
        question.setLesson(lesson);
        question.setType(QuestionType.MULTIPLE_CHOICE);
        question.setQuestionText(text);
        question.setExplanation("Explanation");
        question.setCorrectAnswer("is");
        question.setKnowledgePoint("Subject verb agreement");
        question.setOrderIndex(1);
        question.setDifficulty("EASY");
        question.setXpReward(5);
        question.setStatus(status);
        return questionRepository.saveAndFlush(question);
    }

    private CourseEnrollment saveEnrollment(User student, Course course) {
        return saveEnrollment(student, course, EnrollmentStatus.ACTIVE);
    }

    private CourseEnrollment saveEnrollment(User student, Course course, EnrollmentStatus status) {
        CourseEnrollment enrollment = new CourseEnrollment();
        enrollment.setUser(student);
        enrollment.setCourse(course);
        enrollment.setStatus(status);
        enrollment.setProgressPercent(0);
        return courseEnrollmentRepository.saveAndFlush(enrollment);
    }

    private String loginAndGetAccessToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
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

    private record InputOnlyRequest(String inputText) {}
}
