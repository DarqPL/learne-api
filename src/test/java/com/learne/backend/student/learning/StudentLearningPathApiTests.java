package com.learne.backend.student.learning;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.monetization.entity.AiUsageEvent;
import com.learne.backend.monetization.enums.AiUsageFeature;
import com.learne.backend.monetization.repository.AiUsageEventRepository;
import com.learne.backend.student.learning.ai.AiLearningPathClient;
import com.learne.backend.student.learning.ai.AiLearningPathRequest;
import com.learne.backend.student.learning.ai.AiLearningPathResponse;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import com.learne.backend.student.learning.enums.LearningPathStatus;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.LearningActivityRepository;
import com.learne.backend.student.learning.repository.LearningPathItemRepository;
import com.learne.backend.student.learning.repository.LearningPathRepository;
import com.learne.backend.student.learning.repository.LessonProgressRepository;
import com.learne.backend.student.learning.repository.QuestionAnswerRepository;
import com.learne.backend.student.learning.repository.QuizAttemptRepository;
import com.learne.backend.student.learning.repository.UserStatsRepository;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.QuestionOptionRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
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
class StudentLearningPathApiTests {
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
    @Autowired private LearningActivityRepository learningActivityRepository;
    @Autowired private UserStatsRepository userStatsRepository;
    @Autowired private LearningPathRepository learningPathRepository;
    @Autowired private LearningPathItemRepository learningPathItemRepository;
    @Autowired private AiUsageEventRepository aiUsageEventRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    @MockitoBean private AiLearningPathClient aiLearningPathClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void studentCanGenerateLearningPathFromAiRankedRecommendations() throws Exception {
        LearningPathScenario scenario = createLearningPathScenario();
        String reason = "Review greetings before moving forward.";
        mockAiLearningPath(List.of("greetings"), scenario.secondLessonId(), reason);

        mockMvc.perform(post("/api/student/learning-path/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxItems\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.source").value("AI_SERVICE"))
                .andExpect(jsonPath("$.weaknesses[0]").value("greetings"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].enrollmentId").value(scenario.enrollmentId()))
                .andExpect(jsonPath("$.items[0].lessonId").value(scenario.secondLessonId()))
                .andExpect(jsonPath("$.items[0].reason").value(reason))
                .andExpect(jsonPath("$.items[0].score").value(0.94));
    }

    @Test
    void generateArchivesPreviousActivePath() throws Exception {
        LearningPathScenario scenario = createLearningPathScenario();
        mockAiLearningPath(List.of("greetings"), scenario.secondLessonId(), "First recommendation.");

        mockMvc.perform(post("/api/student/learning-path/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxItems\":8}"))
                .andExpect(status().isOk());

        String updatedReason = "Updated AI recommendation.";
        mockAiLearningPath(List.of("conversation fluency"), scenario.secondLessonId(), updatedReason);

        mockMvc.perform(post("/api/student/learning-path/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxItems\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weaknesses[0]").value("conversation fluency"))
                .andExpect(jsonPath("$.items[0].reason").value(updatedReason));

        assertEquals(1, learningPathRepository.findByUserAndStatus(scenario.student(), LearningPathStatus.ACTIVE).size());
    }

    @Test
    void invalidAiLessonIdsAreRejected() throws Exception {
        LearningPathScenario scenario = createLearningPathScenario();
        when(aiLearningPathClient.generateLearningPath(any(AiLearningPathRequest.class)))
                .thenReturn(new AiLearningPathResponse(
                        List.of("greetings"),
                        List.of(new AiLearningPathResponse.RecommendationPayload(
                                999999L,
                                new BigDecimal("0.9400"),
                                "Invalid lesson id."))));

        mockMvc.perform(post("/api/student/learning-path/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxItems\":8}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generateReturnsTooManyRequestsAtFreeQuotaAndDoesNotCallAi() throws Exception {
        LearningPathScenario scenario = createLearningPathScenario();
        fillFreeQuota(scenario.student(), AiUsageFeature.LEARNING_PATH);

        mockMvc.perform(post("/api/student/learning-path/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxItems\":8}"))
                .andExpect(status().isTooManyRequests());

        verify(aiLearningPathClient, never()).generateLearningPath(any(AiLearningPathRequest.class));
    }

    @Test
    void currentAndNextRecommendationReturnFrontendReadyData() throws Exception {
        LearningPathScenario scenario = createLearningPathScenario();
        String reason = "Practice lesson 2 next.";
        mockAiLearningPath(List.of("greetings"), scenario.secondLessonId(), reason);

        mockMvc.perform(post("/api/student/learning-path/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxItems\":8}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/learning-path/current")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].enrollmentId").value(scenario.enrollmentId()))
                .andExpect(jsonPath("$.items[0].lessonTitle").value("Lesson 2"))
                .andExpect(jsonPath("$.items[0].courseTitle").value("English A1 Foundation"));

        mockMvc.perform(get("/api/student/recommendations/next-lesson")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollmentId").value(scenario.enrollmentId()))
                .andExpect(jsonPath("$.lessonId").value(scenario.secondLessonId()))
                .andExpect(jsonPath("$.reason").value(reason));
    }

    @Test
    void generateSendsOnlyUnlockedOrInProgressLessonsToAi() throws Exception {
        LearningPathScenario scenario = createLearningPathScenario();
        mockAiLearningPath(List.of("greetings"), scenario.secondLessonId(), "Practice lesson 2 next.");

        mockMvc.perform(post("/api/student/learning-path/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxItems\":8}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AiLearningPathRequest> requestCaptor = ArgumentCaptor.forClass(AiLearningPathRequest.class);
        verify(aiLearningPathClient).generateLearningPath(requestCaptor.capture());

        assertEquals(List.of(scenario.secondLessonId()), requestCaptor.getValue().constraints().allowedLessonIds());
        assertEquals(List.of(scenario.secondLessonId()), requestCaptor.getValue().candidateLessons().stream()
                .map(AiLearningPathRequest.CandidateLessonPayload::lessonId)
                .toList());
    }

    @Test
    void currentMarksGeneratedPathItemCompletedAfterLessonProgressCompletes() throws Exception {
        LearningPathScenario scenario = createLearningPathScenario();
        mockAiLearningPath(List.of("greetings"), scenario.secondLessonId(), "Practice lesson 2 next.");

        mockMvc.perform(post("/api/student/learning-path/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxItems\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].completed").value(false));

        long secondQuestionId = firstQuestionId(scenario.secondLessonId());
        submitCorrectAnswer(
                scenario.studentToken(),
                scenario.enrollmentId(),
                scenario.secondLessonId(),
                secondQuestionId,
                correctOptionId(secondQuestionId));

        mockMvc.perform(get("/api/student/learning-path/current")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].lessonId").value(scenario.secondLessonId()))
                .andExpect(jsonPath("$.items[0].completed").value(true));
    }

    private LearningPathScenario createLearningPathScenario() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
        long enrollmentId = enrollAndGetId(studentToken, courseId);
        long firstLessonId = lessonId(enrollmentId, 0);
        long secondLessonId = lessonId(enrollmentId, 1);
        long questionId = firstQuestionId(firstLessonId);

        submitCorrectAnswer(studentToken, enrollmentId, firstLessonId, questionId, correctOptionId(questionId));

        return new LearningPathScenario(student, studentToken, enrollmentId, secondLessonId);
    }

    private void mockAiLearningPath(List<String> weaknesses, long lessonId, String reason) {
        when(aiLearningPathClient.generateLearningPath(any(AiLearningPathRequest.class)))
                .thenReturn(new AiLearningPathResponse(
                        weaknesses,
                        List.of(new AiLearningPathResponse.RecommendationPayload(
                                lessonId,
                                new BigDecimal("0.9400"),
                                reason))));
    }

    private long createApprovedCourseWithTwoLessons(String teacherToken, String adminToken) throws Exception {
        long courseId = createCourse(teacherToken);
        long topicId = createTopic(teacherToken, courseId);
        long firstLessonId = createLesson(teacherToken, topicId, "Lesson 1", 1);
        long secondLessonId = createLesson(teacherToken, topicId, "Lesson 2", 2);
        long thirdLessonId = createLesson(teacherToken, topicId, "Lesson 3", 3);
        createMultipleChoiceQuestion(teacherToken, firstLessonId, 1);
        createMultipleChoiceQuestion(teacherToken, secondLessonId, 2);
        createMultipleChoiceQuestion(teacherToken, thirdLessonId, 3);
        approveCourse(teacherToken, adminToken, courseId);
        return courseId;
    }

    private void approveCourse(String teacherToken, String adminToken, long courseId) throws Exception {
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private long createCourse(String teacherToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/teacher/content/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "English A1 Foundation",
                                  "description": "Starter English course",
                                  "type": "COMPREHENSIVE",
                                  "level": "A1",
                                  "orderIndex": 1
                                }
                                """))
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

    private long enrollAndGetId(String studentToken, long courseId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void submitCorrectAnswer(String studentToken, long enrollmentId, long lessonId, long questionId, long correctOptionId) throws Exception {
        mockMvc.perform(post("/api/student/enrollments/%d/lessons/%d/quiz/submit".formatted(enrollmentId, lessonId))
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

    private long lessonId(long enrollmentId, int lessonIndex) {
        return courseEnrollmentRepository.findById(enrollmentId)
                .map(lessonProgressRepository::findByCourseEnrollmentOrderedForLearning)
                .orElseThrow()
                .get(lessonIndex)
                .getLesson()
                .getId();
    }

    private long firstQuestionId(long lessonId) {
        return questionRepository.findAll().stream()
                .filter(question -> question.getLesson().getId().equals(lessonId))
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

    private void fillFreeQuota(User user, AiUsageFeature feature) {
        for (int i = 0; i < 5; i++) {
            AiUsageEvent event = new AiUsageEvent();
            event.setUser(user);
            event.setFeature(feature);
            event.setUsedAt(Instant.now());
            aiUsageEventRepository.save(event);
        }
    }

    private record LearningPathScenario(User student, String studentToken, long enrollmentId, long secondLessonId) {}
}
