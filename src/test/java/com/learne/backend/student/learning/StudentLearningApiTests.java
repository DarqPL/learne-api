package com.learne.backend.student.learning;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.AccountOrigin;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.monetization.entity.CourseEntitlement;
import com.learne.backend.monetization.enums.CourseEntitlementSource;
import com.learne.backend.monetization.repository.CourseEntitlementRepository;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import com.learne.backend.student.learning.dto.SubmitQuestionAnswerRequest;
import com.learne.backend.student.learning.dto.SubmitQuizRequest;
import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.entity.LearningActivity;
import com.learne.backend.student.learning.entity.UserStats;
import com.learne.backend.student.learning.enums.LearningActivitySourceType;
import com.learne.backend.student.learning.enums.LessonProgressStatus;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.LearningActivityRepository;
import com.learne.backend.student.learning.repository.LearningPathItemRepository;
import com.learne.backend.student.learning.repository.LearningPathRepository;
import com.learne.backend.student.learning.repository.LessonProgressRepository;
import com.learne.backend.student.learning.repository.QuestionAnswerRepository;
import com.learne.backend.student.learning.repository.QuizAttemptRepository;
import com.learne.backend.student.learning.repository.UserStatsRepository;
import com.learne.backend.student.learning.service.StudentQuizGradingService;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.QuestionOptionRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
import com.learne.backend.teacher.content.enrichment.CourseKnowledgePointClient;
import com.learne.backend.teacher.content.enrichment.CourseKnowledgePointRequest;
import com.learne.backend.teacher.content.enrichment.CourseKnowledgePointResponse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
import org.springframework.test.web.servlet.ResultActions;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class StudentLearningApiTests extends com.learne.backend.testsupport.SubmissionHttpPostgresSupport {
    @MockitoBean private com.learne.backend.admin.content.service.ReviewDecisionService reviewDecisions;
    @Autowired private UserRepository userRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuestionOptionRepository questionOptionRepository;
    @Autowired private CourseEnrollmentRepository courseEnrollmentRepository;
    @Autowired private LessonProgressRepository lessonProgressRepository;
    @Autowired private QuizAttemptRepository quizAttemptRepository;
    @Autowired private QuestionAnswerRepository questionAnswerRepository;
    @Autowired private UserStatsRepository userStatsRepository;
    @Autowired private LearningActivityRepository learningActivityRepository;
    @Autowired private CourseEntitlementRepository courseEntitlementRepository;
    @Autowired private LearningPathItemRepository learningPathItemRepository;
    @Autowired private LearningPathRepository learningPathRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;
    @MockitoBean private CourseKnowledgePointClient courseKnowledgePointClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
        when(courseKnowledgePointClient.enrichKnowledgePoints(any(), org.mockito.ArgumentMatchers.eq(com.learne.backend.teacher.content.enrichment.EnrichmentContract.V1))).thenAnswer(invocation -> {
            CourseKnowledgePointRequest request = invocation.getArgument(0);
            return new CourseKnowledgePointResponse(request.questions().stream()
                    .map(question -> new CourseKnowledgePointResponse.Item(
                            question.questionId(), "test_knowledge_point"))
                    .toList());
        });
    }

    @Test
    void studentLearningEndpointsRequireStudentRole() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/student/courses"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/student/courses").header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/student/courses").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/student/courses").header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk());
    }

    @Test
    void mistakeReviewLifecycleIsOwnerScopedAndIdempotentWithoutProgressSideEffects() throws Exception {
        User admin = saveUser("review-admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("review-teacher@example.com", UserRole.TEACHER);
        User student = saveUser("review-student@example.com", UserRole.STUDENT);
        User otherStudent = saveUser("review-other@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        String otherStudentToken = loginAndGetAccessToken(otherStudent.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long lessonId = firstLessonId(enrollmentId);
        long questionId = firstQuestionId(lessonId);
        long wrongOptionId = wrongOptionId(questionId);
        long correctOptionId = correctOptionId(questionId);
        long otherQuestionOptionId = wrongOptionId(firstQuestionId(secondLessonId(enrollmentId)));

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answers":[{"questionId":%d,"selectedOptionId":%d}]}
                                """.formatted(questionId, wrongOptionId)))
                .andExpect(status().isOk());
        int xpBeforeReview = userStatsRepository.findByUser(student).map(UserStats::getTotalXp).orElse(0);

        MvcResult createResult = mockMvc.perform(post("/api/student/mistake-review/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").isNumber())
                .andExpect(jsonPath("$.questionCount").value(1))
                .andExpect(jsonPath("$.questions[0].questionId").value(questionId))
                .andExpect(jsonPath("$.questions[0].correctAnswer").doesNotExist())
                .andReturn();
        long sessionId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("sessionId").asLong();

        mockMvc.perform(get("/api/student/mistake-review/sessions/%d".formatted(sessionId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherStudentToken))
                .andExpect(status().isNotFound());

        String submitPayload = """
                {"answers":[{"questionId":%d,"selectedOptionId":%d}]}
                """.formatted(questionId, correctOptionId);
        mockMvc.perform(post("/api/student/mistake-review/sessions/%d/submit".formatted(sessionId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answers":[{"questionId":%d,"selectedOptionId":%d}]}
                                """.formatted(questionId, otherQuestionOptionId)))
                .andExpect(status().isBadRequest());
        MvcResult submitResult = mockMvc.perform(post("/api/student/mistake-review/sessions/%d/submit".formatted(sessionId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answers[0].correct").value(true))
                .andReturn();

        mockMvc.perform(post("/api/student/mistake-review/sessions/%d/submit".formatted(sessionId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitPayload))
                .andExpect(status().isOk())
                .andExpect(content().json(submitResult.getResponse().getContentAsString()));

        mockMvc.perform(post("/api/student/mistake-review/sessions/%d/submit".formatted(sessionId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answers":[{"questionId":%d,"selectedOptionId":%d}]}
                                """.formatted(questionId, wrongOptionId)))
                .andExpect(status().isConflict());
        assertEquals(xpBeforeReview, userStatsRepository.findByUser(student).orElseThrow().getTotalXp());
    }

    @Test
    void regularQuizAnswersExposeSharedAnswerEventOrder() {
        Integer columns = jdbcTemplate.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_name = 'question_answers'
                  and column_name = 'answer_event_order'
                """,
                Integer.class);

        assertEquals(1, columns);
    }

    @Test
    void enrollApprovedCourseCreatesEnrollmentAndSequentialLessonProgress() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);

        mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId))
                .andExpect(jsonPath("$.courseName").value("English A1 Foundation"))
                .andExpect(jsonPath("$.teacherId").value(teacher.getId()))
                .andExpect(jsonPath("$.teacherName").value("Teacher User"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.progressPercent").value(0))
                .andExpect(jsonPath("$.completedLessonCount").value(0))
                .andExpect(jsonPath("$.totalLessonCount").value(2));

        mockMvc.perform(get("/api/student/enrollments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].courseId").value(courseId));

        Course course = courseRepository.findById(courseId).orElseThrow();
        CourseEnrollment enrollment = courseEnrollmentRepository.findByUserAndCourse(student, course).orElseThrow();
        var lessonProgress = lessonProgressRepository.findByCourseEnrollmentOrderedForLearning(enrollment);
        assertEquals(2, lessonProgress.size());
        assertEquals(LessonProgressStatus.UNLOCKED, lessonProgress.get(0).getStatus());
        assertEquals(LessonProgressStatus.LOCKED, lessonProgress.get(1).getStatus());
    }

    @Test
    void enrollingSameCourseTwiceReturnsSameEnrollmentWithoutDuplicatingProgress() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);

        MvcResult firstResult = mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult secondResult = mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(firstResult.getResponse().getContentAsString());
        JsonNode secondJson = objectMapper.readTree(secondResult.getResponse().getContentAsString());
        assertEquals(firstJson.get("id").asLong(), secondJson.get("id").asLong());

        Course course = courseRepository.findById(courseId).orElseThrow();
        CourseEnrollment enrollment = courseEnrollmentRepository.findByUserAndCourse(student, course).orElseThrow();
        assertEquals(1, courseEnrollmentRepository.countByUser(student));
        assertEquals(2, lessonProgressRepository.findByCourseEnrollmentOrderedForLearning(enrollment).size());
    }

    @Test
    void catalogReturnsOnlyApprovedCoursesWithEnrollmentFlag() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long approvedCourseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        createCourse(teacherToken);

        mockMvc.perform(get("/api/student/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(approvedCourseId))
                .andExpect(jsonPath("$.content[0].name").value("English A1 Foundation"))
                .andExpect(jsonPath("$.content[0].level").value("A1"))
                .andExpect(jsonPath("$.content[0].teacherId").value(teacher.getId()))
                .andExpect(jsonPath("$.content[0].teacherName").value("Teacher User"))
                .andExpect(jsonPath("$.content[0].topicCount").value(1))
                .andExpect(jsonPath("$.content[0].lessonCount").value(2))
                .andExpect(jsonPath("$.content[0].questionCount").value(2))
                .andExpect(jsonPath("$.content[0].enrolled").value(false));

        mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(approvedCourseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(approvedCourseId))
                .andExpect(jsonPath("$.content[0].enrolled").value(true));
    }

    @Test
    void a2PaidCourseIsVisibleButLockedAndCannotBeEnrolledWithoutEntitlement() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 300000L);

        mockMvc.perform(get("/api/student/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(courseId))
                .andExpect(jsonPath("$.content[0].pricingType").value("PAID"))
                .andExpect(jsonPath("$.content[0].priceAmountVnd").value(300000))
                .andExpect(jsonPath("$.content[0].accessStatus").value("LOCKED"))
                .andExpect(jsonPath("$.content[0].accessReason").value("Purchase is required."));

        mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Course access is required."));
    }

    @Test
    void ownerTeacherAccessRequiresTeacherCreatedAccount() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User selfRegisteredStudent = saveUser("self-student@example.com", UserRole.STUDENT);
        User teacherCreatedStudent = saveUser("teacher-student@example.com", UserRole.STUDENT);
        selfRegisteredStudent.setOwnerTeacher(teacher);
        teacherCreatedStudent.setAccountOrigin(AccountOrigin.TEACHER_CREATED);
        teacherCreatedStudent.setOwnerTeacher(teacher);
        userRepository.save(selfRegisteredStudent);
        userRepository.save(teacherCreatedStudent);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String selfRegisteredToken = loginAndGetAccessToken(selfRegisteredStudent.getEmail());
        String teacherCreatedToken = loginAndGetAccessToken(teacherCreatedStudent.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 300000L);

        mockMvc.perform(get("/api/student/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + selfRegisteredToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessStatus").value("LOCKED"));

        mockMvc.perform(get("/api/student/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherCreatedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessStatus").value("OWNED"))
                .andExpect(jsonPath("$.topics", hasSize(1)));
    }

    @Test
    void lockedPaidCourseDetailReturnsMetadataWithoutLearningContent() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 300000L);

        mockMvc.perform(get("/api/student/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(courseId))
                .andExpect(jsonPath("$.name").value("English A2 Premium"))
                .andExpect(jsonPath("$.pricingType").value("PAID"))
                .andExpect(jsonPath("$.priceAmountVnd").value(300000))
                .andExpect(jsonPath("$.accessStatus").value("LOCKED"))
                .andExpect(jsonPath("$.accessReason").value("Purchase is required."))
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.topics", hasSize(0)));
    }

    @Test
    void entitledPaidCourseStudentCanViewContentAndEnroll() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedPaidCourse(teacherToken, adminToken, "English A2 Premium", "A2", 300000L);
        seedCourseEntitlement(student, courseId);

        mockMvc.perform(get("/api/student/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(courseId))
                .andExpect(jsonPath("$.pricingType").value("PAID"))
                .andExpect(jsonPath("$.accessStatus").value("OWNED"))
                .andExpect(jsonPath("$.accessReason").value("Course is unlocked."))
                .andExpect(jsonPath("$.topics", hasSize(1)))
                .andExpect(jsonPath("$.topics[0].lessons", hasSize(1)))
                .andExpect(jsonPath("$.topics[0].lessons[0].content").value("Use hello, hi, and goodbye in short conversations."));

        MvcResult result = mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId))
                .andReturn();
        long enrollmentId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/student/enrollments/%d/progress".formatted(enrollmentId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.course.id").value(courseId))
                .andExpect(jsonPath("$.topics", hasSize(1)));
    }

    @Test
    void a1PaidDataStillBehavesAsFreeForEnrollment() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        jdbcTemplate.update("""
                UPDATE courses
                SET pricing_type = 'PAID', price_amount_vnd = 300000
                WHERE id = ?
                """, courseId);

        mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId));
    }

    @Test
    void catalogFiltersByLevelAndTeacherId() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacherA = saveUser("teacher-a@example.com", UserRole.TEACHER);
        User teacherB = saveUser("teacher-b@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherAToken = loginAndGetAccessToken(teacherA.getEmail());
        String teacherBToken = loginAndGetAccessToken(teacherB.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long teacherACourseId = createApprovedCourseWithTwoLessons(teacherAToken, adminToken);
        createApprovedCourse(teacherBToken, adminToken, "English A2 Foundation", "A2");

        mockMvc.perform(get("/api/student/courses")
                        .queryParam("level", "A1")
                        .queryParam("teacherId", teacherA.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(teacherACourseId))
                .andExpect(jsonPath("$.content[0].level").value("A1"))
                .andExpect(jsonPath("$.content[0].teacherId").value(teacherA.getId()));

        mockMvc.perform(get("/api/student/courses")
                        .queryParam("level", "A2")
                        .queryParam("teacherId", teacherA.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void catalogExcludesApprovedCoursesWithoutPublishedAt() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        jdbcTemplate.update("update courses set published_at = null where id = ?", courseId);

        mockMvc.perform(get("/api/student/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
    }

    @Test
    void catalogCursorPagesAreOrderedBoundToFiltersAndSizeIsClamped() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long firstCourseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long secondCourseId = createApprovedCourse(teacherToken, adminToken, "English A2 Foundation", "A2");
        long thirdCourseId = createApprovedCourse(teacherToken, adminToken, "English B1 Foundation", "B1");

        MvcResult firstPage = mockMvc.perform(get("/api/student/courses")
                        .queryParam("size", "0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn();
        String cursor = objectMapper.readTree(firstPage.getResponse().getContentAsString()).get("nextCursor").asText();
        long firstPageId = objectMapper.readTree(firstPage.getResponse().getContentAsString()).get("content").get(0).get("id").asLong();

        MvcResult secondPage = mockMvc.perform(get("/api/student/courses")
                        .queryParam("size", "1")
                        .queryParam("cursor", cursor)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(org.hamcrest.Matchers.not(firstPageId)))
                .andReturn();
        long secondPageId = objectMapper.readTree(secondPage.getResponse().getContentAsString()).get("content").get(0).get("id").asLong();
        org.junit.jupiter.api.Assertions.assertTrue(
                secondPageId == firstCourseId || secondPageId == secondCourseId || secondPageId == thirdCourseId);

        mockMvc.perform(get("/api/student/courses")
                        .queryParam("level", "A1")
                        .queryParam("size", "1")
                        .queryParam("cursor", cursor)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid cursor."));
    }

    @Test
    void courseDetailReturnsApprovedStructureWithoutAnswerLeakage() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);

        mockMvc.perform(get("/api/student/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(courseId))
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.activeEnrollmentId").doesNotExist())
                .andExpect(jsonPath("$.topics", hasSize(1)))
                .andExpect(jsonPath("$.topics[0].lessons", hasSize(2)))
                .andExpect(jsonPath("$.topics[0].lessons[0].content").value("Use hello, hi, and goodbye in short conversations."))
                .andExpect(jsonPath("$.topics[0].lessons[0].questions", hasSize(1)))
                .andExpect(jsonPath("$.topics[0].lessons[0].questions[0].optionCount").value(2))
                .andExpect(jsonPath("$.topics[0].lessons[0].questions[0].correctAnswer").doesNotExist())
                .andExpect(jsonPath("$.topics[0].lessons[0].questions[0].options").doesNotExist());
    }

    @Test
    void courseDetailSharesPublicContentButKeepsEnrollmentPerStudent() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User firstStudent = saveUser("first-student@example.com", UserRole.STUDENT);
        User secondStudent = saveUser("second-student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String firstToken = loginAndGetAccessToken(firstStudent.getEmail());
        String secondToken = loginAndGetAccessToken(secondStudent.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);

        mockMvc.perform(get("/api/student/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(courseId))
                .andExpect(jsonPath("$.name").value("English A1 Foundation"))
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.activeEnrollmentId").doesNotExist())
                .andExpect(jsonPath("$.topics", hasSize(1)));

        MvcResult enrollmentResult = mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken))
                .andExpect(status().isOk())
                .andReturn();
        long enrollmentId = objectMapper.readTree(enrollmentResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/student/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(courseId))
                .andExpect(jsonPath("$.name").value("English A1 Foundation"))
                .andExpect(jsonPath("$.enrolled").value(true))
                .andExpect(jsonPath("$.activeEnrollmentId").value(enrollmentId))
                .andExpect(jsonPath("$.topics", hasSize(1)));

        mockMvc.perform(get("/api/student/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(courseId))
                .andExpect(jsonPath("$.name").value("English A1 Foundation"))
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.activeEnrollmentId").doesNotExist())
                .andExpect(jsonPath("$.topics", hasSize(1)));
    }

    @Test
    void courseDetailHidesUnapprovedCourseAsNotFound() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long draftCourseId = createCourse(teacherToken);

        mockMvc.perform(get("/api/student/courses/%d".formatted(draftCourseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Course not found."));
    }

    @Test
    void progressDetailReturnsOrderedLessonStatusesForCurrentStudent() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);

        mockMvc.perform(get("/api/student/enrollments/%d/progress".formatted(enrollmentId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollmentId").value(enrollmentId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.progressPercent").value(0))
                .andExpect(jsonPath("$.completedLessonCount").value(0))
                .andExpect(jsonPath("$.totalLessonCount").value(2))
                .andExpect(jsonPath("$.course.id").value(courseId))
                .andExpect(jsonPath("$.topics", hasSize(1)))
                .andExpect(jsonPath("$.topics[0].lessons", hasSize(2)))
                .andExpect(jsonPath("$.topics[0].lessons[0].content").value("Use hello, hi, and goodbye in short conversations."))
                .andExpect(jsonPath("$.topics[0].lessons[0].status").value("UNLOCKED"))
                .andExpect(jsonPath("$.topics[0].lessons[1].status").value("LOCKED"));
    }

    @Test
    void staleEnrollmentCannotAccessLearningContentAfterCourseBecomesPaid() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourse(teacherToken, adminToken, "English A2 Foundation", "A2");
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);
        long questionId = firstQuestionId(firstLessonId);
        long wrongOptionId = wrongOptionId(questionId);
        MvcResult attemptResult = mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": [
                                    { "questionId": %d, "selectedOptionId": %d }
                                  ]
                                }
                                """.formatted(questionId, wrongOptionId)))
                .andExpect(status().isOk())
                .andReturn();
        long attemptId = objectMapper.readTree(attemptResult.getResponse().getContentAsString()).get("attemptId").asLong();

        jdbcTemplate.update("""
                UPDATE courses
                SET pricing_type = 'PAID', price_amount_vnd = 300000
                WHERE id = ?
                """, courseId);

        mockMvc.perform(get("/api/student/enrollments/%d/progress".formatted(enrollmentId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Course access is required."));

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/start".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Course access is required."));

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/complete".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Course access is required."));

        mockMvc.perform(get("/api/student/enrollments/%d/lessons/%d/quiz".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Course access is required."));

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": [
                                    { "questionId": %d, "selectedOptionId": %d }
                                  ]
                                }
                                """.formatted(questionId, wrongOptionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Course access is required."));

        mockMvc.perform(get("/api/student/quiz-attempts/%d".formatted(attemptId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Course access is required."));
    }

    @Test
    void lessonAndQuizResponsesExposeListeningAudio() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);

        jdbcTemplate.update("""
                UPDATE lessons
                SET skill_type = 'LISTENING', audio_url = ?, audio_public_id = ?
                WHERE id = ?
                """, "https://res.cloudinary.com/learne/video/upload/listening-audio/sample.mp3",
                "learne/listening-audio/sample", firstLessonId);

        mockMvc.perform(get("/api/student/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics[0].lessons[0].skillType").value("LISTENING"))
                .andExpect(jsonPath("$.topics[0].lessons[0].audioUrl")
                        .value("https://res.cloudinary.com/learne/video/upload/listening-audio/sample.mp3"))
                .andExpect(jsonPath("$.topics[0].lessons[0].audioPublicId")
                        .value("learne/listening-audio/sample"));

        mockMvc.perform(get("/api/student/enrollments/%d/progress".formatted(enrollmentId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics[0].lessons[0].skillType").value("LISTENING"))
                .andExpect(jsonPath("$.topics[0].lessons[0].audioUrl")
                        .value("https://res.cloudinary.com/learne/video/upload/listening-audio/sample.mp3"))
                .andExpect(jsonPath("$.topics[0].lessons[0].audioPublicId")
                        .value("learne/listening-audio/sample"));

        mockMvc.perform(get("/api/student/enrollments/%d/lessons/%d/quiz".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audioUrl")
                        .value("https://res.cloudinary.com/learne/video/upload/listening-audio/sample.mp3"))
                .andExpect(jsonPath("$.audioPublicId")
                        .value("learne/listening-audio/sample"));
    }

    @Test
    void studentCannotAccessAnotherStudentsEnrollment() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User owner = saveUser("owner@example.com", UserRole.STUDENT);
        User other = saveUser("other@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String ownerToken = loginAndGetAccessToken(owner.getEmail());
        String otherToken = loginAndGetAccessToken(other.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(ownerToken, courseId);

        mockMvc.perform(get("/api/student/enrollments/%d/progress".formatted(enrollmentId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Enrollment not found."));
    }

    @Test
    void enrollIsIdempotentForSameStudentAndCourse() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);

        long firstEnrollmentId = enrollAndGetId(studentToken, courseId);
        long secondEnrollmentId = enrollAndGetId(studentToken, courseId);

        assertEquals(firstEnrollmentId, secondEnrollmentId);
        assertEquals(1, courseEnrollmentRepository.findAll().size());
        assertEquals(2, lessonProgressRepository.findAll().size());
    }

    @Test
    void enrollUnapprovedCourseReturnsNotFound() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long draftCourseId = createCourse(teacherToken);

        mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(draftCourseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Course not found."));
    }

    @Test
    void startUnlockedLessonMovesItToInProgress() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/start".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics[0].lessons[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.topics[0].lessons[0].startedAt").exists());
    }

    @Test
    void startLockedLessonReturnsBadRequest() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long secondLessonId = secondLessonId(enrollmentId);

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/start".formatted(enrollmentId, secondLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Lesson is locked."));
    }

    @Test
    void completeLessonRecomputesCourseProgressAndUnlocksNextLesson() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedTargetOnlyCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/complete".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.progressPercent").value(50))
                .andExpect(jsonPath("$.completedLessonCount").value(1))
                .andExpect(jsonPath("$.topics[0].lessons[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.topics[0].lessons[1].status").value("UNLOCKED"));
    }

    @Test
    void completeFinalLessonMarksEnrollmentCompleted() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedTargetOnlyCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);
        long secondLessonId = secondLessonId(enrollmentId);

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/complete".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/complete".formatted(enrollmentId, secondLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.progressPercent").value(100))
                .andExpect(jsonPath("$.completedLessonCount").value(2));
    }

    @Test
    void completeLockedLessonReturnsBadRequest() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedTargetOnlyCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long secondLessonId = secondLessonId(enrollmentId);

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/complete".formatted(enrollmentId, secondLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Lesson is locked."));
    }

    @Test
    void lessonOutsideEnrollmentCourseReturnsNotFound() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long firstCourseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long secondCourseId = createApprovedCourse(teacherToken, adminToken, "English A2 Foundation", "A2");
        long enrollmentId = enrollAndGetId(studentToken, firstCourseId);
        long otherEnrollmentId = enrollAndGetId(studentToken, secondCourseId);
        long otherLessonId = firstLessonId(otherEnrollmentId);

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/start".formatted(enrollmentId, otherLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Lesson not found."));
    }

    @Test
    void statsEndpointReturnsDefaultStatsForNewStudent() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/student/stats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalXp").value(0))
                .andExpect(jsonPath("$.currentStreakDays").value(0))
                .andExpect(jsonPath("$.longestStreakDays").value(0))
                .andExpect(jsonPath("$.lastActivityDate").doesNotExist());

        assertEquals(1, userStatsRepository.findAll().size());
    }

    @Test
    void quizEndpointReturnsQuestionsAndOptionsWithoutAnswerLeakage() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);

        mockMvc.perform(get("/api/student/enrollments/%d/lessons/%d/quiz".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollmentId").value(enrollmentId))
                .andExpect(jsonPath("$.lessonId").value(firstLessonId))
                .andExpect(jsonPath("$.passPercent").value(70))
                .andExpect(jsonPath("$.bestScorePercent").doesNotExist())
                .andExpect(jsonPath("$.alreadyPassed").value(false))
                .andExpect(jsonPath("$.questions", hasSize(1)))
                .andExpect(jsonPath("$.questions[0].type").value("MULTIPLE_CHOICE"))
                .andExpect(jsonPath("$.questions[0].correctAnswer").doesNotExist())
                .andExpect(jsonPath("$.questions[0].options", hasSize(2)))
                .andExpect(jsonPath("$.questions[0].options[0].correct").doesNotExist());
    }

    @Test
    void quizEndpointAndSubmitIgnoreInactiveOptions() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);
        long questionId = firstQuestionId(firstLessonId);
        long inactiveOptionId = wrongOptionId(questionId);
        jdbcTemplate.update("update question_options set is_active = false where id = ?", inactiveOptionId);

        mockMvc.perform(get("/api/student/enrollments/%d/lessons/%d/quiz".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].options", hasSize(1)))
                .andExpect(jsonPath("$.questions[0].options[0].id").value(correctOptionId(questionId)));

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": [
                                    { "questionId": %d, "selectedOptionId": %d }
                                  ]
                                }
                                """.formatted(questionId, inactiveOptionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Option does not belong to this question."));
    }

    @Test
    void submitRejectsDuplicateQuestionIdsAsMissingRequiredAnswer() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);
        long questionId = firstQuestionId(firstLessonId);
        long correctOptionId = correctOptionId(questionId);

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": [
                                    { "questionId": %d, "selectedOptionId": %d },
                                    { "questionId": %d, "selectedOptionId": %d }
                                  ]
                                }
                                """.formatted(questionId, correctOptionId, questionId, correctOptionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Answer is required for every question."));
    }

    @Test
    void submitRejectsSelectedOptionFromAnotherQuestion() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);
        long secondLessonId = secondLessonId(enrollmentId);
        long firstQuestionId = firstQuestionId(firstLessonId);
        long secondQuestionId = firstQuestionId(secondLessonId);
        long otherQuestionOptionId = correctOptionId(secondQuestionId);

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": [
                                    { "questionId": %d, "selectedOptionId": %d }
                                  ]
                                }
                                """.formatted(firstQuestionId, otherQuestionOptionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Option does not belong to this question."));
    }

    @Test
    void quizFetchAndSubmitRejectLockedLesson() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long secondLessonId = secondLessonId(enrollmentId);
        long questionId = firstQuestionId(secondLessonId);
        long correctOptionId = correctOptionId(questionId);

        mockMvc.perform(get("/api/student/enrollments/%d/lessons/%d/quiz".formatted(enrollmentId, secondLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Lesson is locked."));

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, secondLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": [
                                    { "questionId": %d, "selectedOptionId": %d }
                                  ]
                                }
                                """.formatted(questionId, correctOptionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Lesson is locked."));
    }

    @Test
    void completeQuizLessonReturnsBadRequestAndDoesNotUnlockNextLesson() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/complete".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Lesson quiz must be passed."));

        CourseEnrollment enrollment = courseEnrollmentRepository.findById(enrollmentId).orElseThrow();
        var lessonProgress = lessonProgressRepository.findByCourseEnrollmentOrderedForLearning(enrollment);
        assertEquals(LessonProgressStatus.UNLOCKED, lessonProgress.get(0).getStatus());
        assertEquals(LessonProgressStatus.LOCKED, lessonProgress.get(1).getStatus());
        assertEquals(0, enrollment.getProgressPercent());
    }

    @Test
    void studentQuizAndStatsEndpointsRequireStudentRole() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/student/enrollments/1/lessons/1/quiz"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/student/enrollments/1/lessons/1/quiz")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/student/enrollments/1/lessons/1/quiz/submit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/student/enrollments/1/lessons/1/quiz")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/student/stats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk());
    }

    @Test
    void submitPronunciationOnlyLessonRejectsNoGradableQuestions() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createCourseWithSingleLesson(teacherToken, "Pronunciation Course", "Pronunciation Lesson");
        long lessonId = onlyLessonId(courseId);
        long pronunciationQuestionId = createPronunciationQuestion(teacherToken, lessonId, 1);
        approveCourse(teacherToken, adminToken, courseId);
        long enrollmentId = enrollAndGetId(studentToken, courseId);

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": [
                                    { "questionId": %d, "answerText": "Hello world" }
                                  ]
                                }
                                """.formatted(pronunciationQuestionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Quiz has no gradable questions."));
    }

    @Test
    void submitTextAnswerUsesExactMatchWithTrimLowercaseAndRepeatedWhitespaceNormalization() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createCourseWithSingleLesson(teacherToken, "Text Course", "Text Lesson");
        long lessonId = onlyLessonId(courseId);
        long questionId = createTextQuestion(teacherToken, lessonId, 1, "Hello world");
        approveCourse(teacherToken, adminToken, courseId);
        long enrollmentId = enrollAndGetId(studentToken, courseId);

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": [
                                    { "questionId": %d, "answerText": "  hello   WORLD  " }
                                  ]
                                }
                                """.formatted(questionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scorePercent").value(100))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.answers[0].correct").value(true))
                .andExpect(jsonPath("$.answers[0].scorePercent").value(100))
                .andExpect(jsonPath("$.answers[0].gradingMethod").value("EXACT_MATCH"));
    }

    @Test
    void pronunciationQuestionsExposeTargetTextAndAreRecordedWithoutAffectingOverallScore() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createCourseWithSingleLesson(teacherToken, "Mixed Course", "Mixed Lesson");
        long lessonId = onlyLessonId(courseId);
        long textQuestionId = createTextQuestion(teacherToken, lessonId, 1, "Hello world");
        long pronunciationQuestionId = createPronunciationQuestion(teacherToken, lessonId, 2);
        approveCourse(teacherToken, adminToken, courseId);
        long enrollmentId = enrollAndGetId(studentToken, courseId);

        mockMvc.perform(get("/api/student/enrollments/%d/lessons/%d/quiz".formatted(enrollmentId, lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions", hasSize(2)))
                .andExpect(jsonPath("$.questions[1].type").value("PRONUNCIATION"))
                .andExpect(jsonPath("$.questions[1].targetText").value("Hello world"))
                .andExpect(jsonPath("$.questions[1].correctAnswer").doesNotExist());

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": [
                                    { "questionId": %d, "answerText": "Hello world" },
                                    { "questionId": %d, "answerText": "audio-placeholder" }
                                  ]
                                }
                                """.formatted(textQuestionId, pronunciationQuestionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scorePercent").value(100))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.answers", hasSize(2)))
                .andExpect(jsonPath("$.answers[0].questionId").value(textQuestionId))
                .andExpect(jsonPath("$.answers[0].scorePercent").value(100))
                .andExpect(jsonPath("$.answers[1].questionId").value(pronunciationQuestionId))
                .andExpect(jsonPath("$.answers[1].scorePercent").value(0))
                .andExpect(jsonPath("$.answers[1].gradingMethod").value("MANUAL_TARGET_ONLY"));
    }

    @Test
    void submitFailSavesAttemptAndKeepsLessonInProgressWithoutXpOrStreak() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);
        long questionId = firstQuestionId(firstLessonId);
        long wrongOptionId = wrongOptionId(questionId);

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": [
                                    { "questionId": %d, "selectedOptionId": %d }
                                  ]
                                }
                                """.formatted(questionId, wrongOptionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").exists())
                .andExpect(jsonPath("$.enrollmentId").value(enrollmentId))
                .andExpect(jsonPath("$.lessonId").value(firstLessonId))
                .andExpect(jsonPath("$.scorePercent").value(0))
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.earnedXp").value(0))
                .andExpect(jsonPath("$.xpAwarded").value(false))
                .andExpect(jsonPath("$.progress.lessonStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.progress.enrollmentProgressPercent").value(0))
                .andExpect(jsonPath("$.progress.unlockedNextLessonId").doesNotExist())
                .andExpect(jsonPath("$.stats.totalXp").value(0))
                .andExpect(jsonPath("$.stats.currentStreakDays").value(0))
                .andExpect(jsonPath("$.answers[0].questionId").value(questionId))
                .andExpect(jsonPath("$.answers[0].type").value("MULTIPLE_CHOICE"))
                .andExpect(jsonPath("$.answers[0].questionText").value("Choose the correct greeting."))
                .andExpect(jsonPath("$.answers[0].answerText").doesNotExist())
                .andExpect(jsonPath("$.answers[0].selectedOptionId").value(wrongOptionId))
                .andExpect(jsonPath("$.answers[0].selectedOptionText").value("Apple"))
                .andExpect(jsonPath("$.answers[0].correct").value(false))
                .andExpect(jsonPath("$.answers[0].scorePercent").value(0))
                .andExpect(jsonPath("$.answers[0].earnedXp").value(0))
                .andExpect(jsonPath("$.answers[0].gradingMethod").value("OPTION_MATCH"))
                .andExpect(jsonPath("$.answers[0].feedback").value("Incorrect."))
                .andExpect(jsonPath("$.answers[0].correctAnswer").doesNotExist())
                .andExpect(jsonPath("$.answers[0].correctOptionId").exists())
                .andExpect(jsonPath("$.answers[0].correctOptionText").value("Hello"))
                .andExpect(jsonPath("$.answers[0].explanation").value("Hello is a greeting."));

        assertEquals(1, quizAttemptRepository.findAll().size());
        assertEquals(1, questionAnswerRepository.findAll().size());
        assertEquals(0, learningActivityRepository.findAll().size());
    }

    @Test
    void submitPassCompletesLessonUnlocksNextLessonAwardsXpAndStartsStreak() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);
        long secondLessonId = secondLessonId(enrollmentId);
        long questionId = firstQuestionId(firstLessonId);
        long correctOptionId = correctOptionId(questionId);

        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, firstLessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": [
                                    { "questionId": %d, "selectedOptionId": %d }
                                  ]
                                }
                                """.formatted(questionId, correctOptionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scorePercent").value(100))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.earnedXp").value(30))
                .andExpect(jsonPath("$.xpAwarded").value(true))
                .andExpect(jsonPath("$.progress.lessonStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.progress.enrollmentProgressPercent").value(50))
                .andExpect(jsonPath("$.progress.unlockedNextLessonId").value(secondLessonId))
                .andExpect(jsonPath("$.stats.totalXp").value(30))
                .andExpect(jsonPath("$.stats.currentStreakDays").value(1))
                .andExpect(jsonPath("$.stats.longestStreakDays").value(1));

        assertEquals(1, learningActivityRepository.findAll().size());
    }

    @Test
    void submitPassAfterYesterdayActivityIncrementsCurrentStreakWithoutChangingLongerBest() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);
        long questionId = firstQuestionId(firstLessonId);
        long correctOptionId = correctOptionId(questionId);
        seedStats(student, LocalDate.now().minusDays(1), 3, 5);

        submitCorrectAnswer(studentToken, enrollmentId, firstLessonId, questionId, correctOptionId)
                .andExpect(jsonPath("$.stats.currentStreakDays").value(4))
                .andExpect(jsonPath("$.stats.longestStreakDays").value(5));
    }

    @Test
    void submitPassAfterStreakGapResetsCurrentStreakWithoutChangingLongerBest() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);
        long questionId = firstQuestionId(firstLessonId);
        long correctOptionId = correctOptionId(questionId);
        seedStats(student, LocalDate.now().minusDays(3), 3, 5);

        submitCorrectAnswer(studentToken, enrollmentId, firstLessonId, questionId, correctOptionId)
                .andExpect(jsonPath("$.stats.currentStreakDays").value(1))
                .andExpect(jsonPath("$.stats.longestStreakDays").value(5));
    }

    @Test
    void submitPassSecondLessonOnSameDayAddsXpWithoutIncrementingStreakAgain() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);
        long secondLessonId = secondLessonId(enrollmentId);
        long firstQuestionId = firstQuestionId(firstLessonId);
        long secondQuestionId = firstQuestionId(secondLessonId);

        submitCorrectAnswer(studentToken, enrollmentId, firstLessonId, firstQuestionId, correctOptionId(firstQuestionId))
                .andExpect(jsonPath("$.stats.totalXp").value(30))
                .andExpect(jsonPath("$.stats.currentStreakDays").value(1));

        submitCorrectAnswer(studentToken, enrollmentId, secondLessonId, secondQuestionId, correctOptionId(secondQuestionId))
                .andExpect(jsonPath("$.stats.totalXp").value(60))
                .andExpect(jsonPath("$.stats.currentStreakDays").value(1))
                .andExpect(jsonPath("$.stats.longestStreakDays").value(1));

        assertEquals(2, learningActivityRepository.findAll().size());
    }

    @Test
    void multiplePassingAttemptsDoNotAwardXpTwiceAndAttemptResultIsOwnerOnly() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        User otherStudent = saveUser("other@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        String otherToken = loginAndGetAccessToken(otherStudent.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);
        long questionId = firstQuestionId(firstLessonId);
        long correctOptionId = correctOptionId(questionId);

        submitCorrectAnswer(studentToken, enrollmentId, firstLessonId, questionId, correctOptionId)
                .andExpect(jsonPath("$.xpAwarded").value(true));
        MvcResult second = submitCorrectAnswer(studentToken, enrollmentId, firstLessonId, questionId, correctOptionId)
                .andExpect(jsonPath("$.xpAwarded").value(false))
                .andExpect(jsonPath("$.earnedXp").value(0))
                .andExpect(jsonPath("$.answers[0].earnedXp").value(0))
                .andExpect(jsonPath("$.stats.totalXp").value(30))
                .andReturn();
        long attemptId = objectMapper.readTree(second.getResponse().getContentAsString()).get("attemptId").asLong();

        mockMvc.perform(get("/api/student/quiz-attempts/%d".formatted(attemptId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(attemptId))
                .andExpect(jsonPath("$.xpAwarded").doesNotExist())
                .andExpect(jsonPath("$.earnedXp").value(0))
                .andExpect(jsonPath("$.answers[0].earnedXp").value(0))
                .andExpect(jsonPath("$.answers[0].selectedOptionId").value(correctOptionId))
                .andExpect(jsonPath("$.answers[0].selectedOptionText").value("Hello"))
                .andExpect(jsonPath("$.answers[0].correctOptionId").value(correctOptionId))
                .andExpect(jsonPath("$.answers[0].correctOptionText").value("Hello"));

        mockMvc.perform(get("/api/student/quiz-attempts/%d".formatted(attemptId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Quiz attempt not found."));

        var savedAttempt = quizAttemptRepository.findById(attemptId).orElseThrow();
        assertEquals(0, savedAttempt.getEarnedXp());
        assertEquals(0, questionAnswerRepository.findByQuizAttemptOrderByQuestionOrderIndexAscQuestionIdAsc(savedAttempt)
                .get(0).getEarnedXp());
    }

    @Test
    void firstPassingAttemptPersistsZeroXpWhenAwardAlreadyExists() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = firstLessonId(enrollmentId);
        long questionId = firstQuestionId(firstLessonId);
        long correctOptionId = correctOptionId(questionId);
        seedStats(student, LocalDate.now(), 1, 1);
        seedLessonPassActivity(student, firstLessonId);

        MvcResult result = submitCorrectAnswer(studentToken, enrollmentId, firstLessonId, questionId, correctOptionId)
                .andExpect(jsonPath("$.xpAwarded").value(false))
                .andExpect(jsonPath("$.earnedXp").value(0))
                .andExpect(jsonPath("$.answers[0].earnedXp").value(0))
                .andExpect(jsonPath("$.stats.totalXp").value(0))
                .andReturn();
        long attemptId = objectMapper.readTree(result.getResponse().getContentAsString()).get("attemptId").asLong();

        var savedAttempt = quizAttemptRepository.findById(attemptId).orElseThrow();
        assertEquals(0, savedAttempt.getEarnedXp());
        assertEquals(0, questionAnswerRepository.findByQuizAttemptOrderByQuestionOrderIndexAscQuestionIdAsc(savedAttempt)
                .get(0).getEarnedXp());
    }

    @Test
    void gradingRejectsNullAnswerElementAsBadRequest() {
        StudentQuizGradingService gradingService = new StudentQuizGradingService();
        SubmitQuizRequest request = new SubmitQuizRequest(Arrays.asList((SubmitQuestionAnswerRequest) null));

        ApiException exception = assertThrows(ApiException.class,
                () -> gradingService.grade(new Lesson(), List.of(), request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("Answer is required for every question.", exception.getMessage());
    }

    private long createApprovedCourseWithTwoLessons(String teacherToken, String adminToken) throws Exception {
        long courseId = createCourse(teacherToken);
        long topicId = createTopic(teacherToken, courseId);
        long firstLessonId = createLesson(teacherToken, topicId, "First Lesson", 1);
        long secondLessonId = createLesson(teacherToken, topicId, "Second Lesson", 2);
        createMultipleChoiceQuestion(teacherToken, firstLessonId, 1);
        createMultipleChoiceQuestion(teacherToken, secondLessonId, 2);
        approveCourse(teacherToken, adminToken, courseId);

        return courseId;
    }

    private long createApprovedTargetOnlyCourseWithTwoLessons(String teacherToken, String adminToken) throws Exception {
        long courseId = createCourse(teacherToken);
        long topicId = createTopic(teacherToken, courseId);
        long firstLessonId = createLesson(teacherToken, topicId, "First Lesson", 1);
        long secondLessonId = createLesson(teacherToken, topicId, "Second Lesson", 2);
        createPronunciationQuestion(teacherToken, firstLessonId, 1);
        createPronunciationQuestion(teacherToken, secondLessonId, 2);
        approveCourse(teacherToken, adminToken, courseId);

        return courseId;
    }

    private long createCourseWithSingleLesson(String teacherToken, String courseName, String lessonTitle) throws Exception {
        long courseId = createCourse(teacherToken, courseName, "A1");
        long topicId = createTopic(teacherToken, courseId);
        createLesson(teacherToken, topicId, lessonTitle, 1);
        return courseId;
    }

    private long createApprovedCourse(String teacherToken, String adminToken, String name, String level) throws Exception {
        long courseId = createCourse(teacherToken, name, level);
        long topicId = createTopic(teacherToken, courseId);
        long lessonId = createLesson(teacherToken, topicId, "Catalog Lesson", 1);
        createMultipleChoiceQuestion(teacherToken, lessonId, 1);
        approveCourse(teacherToken, adminToken, courseId);

        return courseId;
    }

    private long createApprovedPaidCourse(String teacherToken, String adminToken, String name, String level,
                                          long proposedPriceAmountVnd) throws Exception {
        long courseId = createCourse(teacherToken, name, level, "PAID", proposedPriceAmountVnd);
        long topicId = createTopic(teacherToken, courseId);
        long lessonId = createLesson(teacherToken, topicId, "Catalog Lesson", 1);
        createMultipleChoiceQuestion(teacherToken, lessonId, 1);
        approveCourse(teacherToken, adminToken, courseId);

        return courseId;
    }

    private void approveCourse(String teacherToken, String adminToken, long courseId) throws Exception {
        submitAndProcess(mockMvc, teacherToken, courseId);

        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private long createCourse(String teacherToken) throws Exception {
        return createCourse(teacherToken, "English A1 Foundation", "A1");
    }

    private long createCourse(String teacherToken, String name, String level) throws Exception {
        return createCourse(teacherToken, name, level, null, null);
    }

    private long createCourse(String teacherToken, String name, String level, String pricingType,
                              Long proposedPriceAmountVnd) throws Exception {
        String pricingJson = pricingType == null ? "" : ",\n  \"pricingType\": \"%s\",\n  \"proposedPriceAmountVnd\": %d"
                .formatted(pricingType, proposedPriceAmountVnd);
        MvcResult result = mockMvc.perform(post("/api/teacher/content/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "description": "Starter English course",
                                  "type": "COMPREHENSIVE",
                                  "level": "%s",
                                  "orderIndex": 1
                                  %s
                                }
                                """.formatted(name, level, pricingJson)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createTopic(String teacherToken, long courseId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/teacher/content/courses/%d/topics".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Greetings",
                                  "description": "Basic greetings",
                                  "orderIndex": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createLesson(String teacherToken, long topicId, String title, int orderIndex) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/teacher/content/topics/%d/lessons".formatted(topicId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "%s",
                                  "description": "Practice greeting language",
                                  "content": "Use hello, hi, and goodbye in short conversations.",
                                  "skillType": "SPEAKING",
                                  "orderIndex": %d,
                                  "xpReward": 20,
                                  "placementTest": false
                                }
                                """.formatted(title, orderIndex)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void createMultipleChoiceQuestion(String teacherToken, long lessonId, int orderIndex) throws Exception {
        mockMvc.perform(post("/api/teacher/content/lessons/%d/questions".formatted(lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "MULTIPLE_CHOICE",
                                  "questionText": "Choose the correct greeting.",
                                  "explanation": "Hello is a greeting.",
                                  "knowledgePoint": "greetings",
                                  "orderIndex": %d,
                                  "difficulty": "easy",
                                  "xpReward": 5,
                                  "options": [
                                    { "optionText": "Hello", "correct": true, "orderIndex": 1 },
                                    { "optionText": "Apple", "correct": false, "orderIndex": 2 }
                                  ]
                                }
                                """.formatted(orderIndex)))
                .andExpect(status().isOk());
    }

    private long createTextQuestion(String teacherToken, long lessonId, int orderIndex, String correctAnswer) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/teacher/content/lessons/%d/questions".formatted(lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "SHORT_ANSWER",
                                  "questionText": "Type the greeting.",
                                  "explanation": "Use the target phrase.",
                                  "correctAnswer": "%s",
                                  "knowledgePoint": "greetings",
                                  "orderIndex": %d,
                                  "difficulty": "easy",
                                  "xpReward": 5
                                }
                                """.formatted(correctAnswer, orderIndex)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createPronunciationQuestion(String teacherToken, long lessonId, int orderIndex) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/teacher/content/lessons/%d/questions".formatted(lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "PRONUNCIATION",
                                  "questionText": "Say the target phrase.",
                                  "explanation": "Pronounce each word clearly.",
                                  "correctAnswer": "Hello world",
                                  "knowledgePoint": "pronunciation",
                                  "orderIndex": %d,
                                  "difficulty": "easy",
                                  "xpReward": 5
                                }
                                """.formatted(orderIndex)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long enrollAndGetId(String studentToken, long courseId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private ResultActions submitCorrectAnswer(String studentToken, long enrollmentId, long lessonId, long questionId, long correctOptionId) throws Exception {
        return mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": [
                                    { "questionId": %d, "selectedOptionId": %d }
                                  ]
                                }
                                """.formatted(questionId, correctOptionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));
    }

    private long firstLessonId(long enrollmentId) {
        CourseEnrollment enrollment = courseEnrollmentRepository.findById(enrollmentId).orElseThrow();
        return lessonProgressRepository.findByCourseEnrollmentOrderedForLearning(enrollment).get(0).getLesson().getId();
    }

    private long secondLessonId(long enrollmentId) {
        CourseEnrollment enrollment = courseEnrollmentRepository.findById(enrollmentId).orElseThrow();
        return lessonProgressRepository.findByCourseEnrollmentOrderedForLearning(enrollment).get(1).getLesson().getId();
    }

    private long onlyLessonId(long courseId) {
        return lessonRepository.findAll().stream()
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private void seedStats(User student, LocalDate lastActivityDate, int currentStreakDays, int longestStreakDays) {
        UserStats stats = new UserStats();
        stats.setUser(student);
        stats.setTotalXp(0);
        stats.setCurrentStreakDays(currentStreakDays);
        stats.setLongestStreakDays(longestStreakDays);
        stats.setLastActivityDate(lastActivityDate);
        userStatsRepository.save(stats);
    }

    private void seedLessonPassActivity(User student, long lessonId) {
        LearningActivity activity = new LearningActivity();
        activity.setUser(student);
        activity.setSourceType(LearningActivitySourceType.LESSON_QUIZ_PASS);
        activity.setSourceId(lessonId);
        activity.setActivityDate(LocalDate.now());
        activity.setXpEarned(25);
        learningActivityRepository.save(activity);
    }

    private void seedCourseEntitlement(User student, long courseId) {
        CourseEntitlement entitlement = new CourseEntitlement();
        entitlement.setUser(student);
        entitlement.setCourse(courseRepository.findById(courseId).orElseThrow());
        entitlement.setSource(CourseEntitlementSource.COURSE_PURCHASE);
        entitlement.setStartsAt(Instant.now().minusSeconds(60));
        courseEntitlementRepository.save(entitlement);
    }

    private long firstQuestionId(long lessonId) {
        return questionRepository.findAll().stream()
                .filter(question -> question.getLesson().getId().equals(lessonId))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private long wrongOptionId(long questionId) {
        return questionOptionRepository.findAll().stream()
                .filter(option -> option.getQuestion().getId().equals(questionId) && !option.isCorrect())
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private long correctOptionId(long questionId) {
        return questionOptionRepository.findAll().stream()
                .filter(option -> option.getQuestion().getId().equals(questionId) && option.isCorrect())
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private User saveUser(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(role == UserRole.TEACHER ? "Teacher User" : role.name() + " User");
        user.setPasswordHash(passwordEncoder.encode("Password123"));
        user.setRole(role);
        user.setActive(true);
        user.setLocked(false);
        user.setMustChangePassword(false);
        return userRepository.save(user);
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
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }
}
