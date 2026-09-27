package com.learne.backend.student.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.monetization.entity.AiUsageEvent;
import com.learne.backend.monetization.enums.AiUsageFeature;
import com.learne.backend.monetization.repository.AiUsageEventRepository;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import com.learne.backend.student.learning.ai.AiCourseRecommendationRequest;
import com.learne.backend.student.learning.ai.AiCourseRecommendationResponse;
import com.learne.backend.student.learning.ai.AiLearningPathClient;
import com.learne.backend.student.learning.entity.PlacementAttempt;
import com.learne.backend.student.learning.entity.PlacementAttemptAnswer;
import com.learne.backend.student.learning.entity.PlacementAttemptQuestion;
import com.learne.backend.student.learning.entity.CourseRecommendationPath;
import com.learne.backend.student.learning.enums.LearningPathSource;
import com.learne.backend.student.learning.enums.LearningPathStatus;
import com.learne.backend.student.learning.enums.PlacementAttemptStatus;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.CourseRecommendationItemRepository;
import com.learne.backend.student.learning.repository.CourseRecommendationPathRepository;
import com.learne.backend.student.learning.repository.PlacementAttemptAnswerRepository;
import com.learne.backend.student.learning.repository.PlacementAttemptQuestionRepository;
import com.learne.backend.student.learning.repository.PlacementAttemptRepository;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.enums.SkillType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.QuestionOptionRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class StudentCourseRecommendationApiTests {
    @Autowired private UserRepository userRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuestionOptionRepository questionOptionRepository;
    @Autowired private PlacementAttemptRepository placementAttemptRepository;
    @Autowired private PlacementAttemptQuestionRepository placementAttemptQuestionRepository;
    @Autowired private PlacementAttemptAnswerRepository placementAttemptAnswerRepository;
    @Autowired private CourseEnrollmentRepository courseEnrollmentRepository;
    @Autowired private CourseRecommendationPathRepository courseRecommendationPathRepository;
    @Autowired private CourseRecommendationItemRepository courseRecommendationItemRepository;
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
    void generateCourseRecommendationsReturnsMultipleCoursesFromAi() throws Exception {
        CourseRecommendationScenario scenario = createSubmittedPlacementAndApprovedCoursesScenario();
        mockAiCourseRecommendations(List.of(
                recommendation(scenario.grammarCourseId(), "Grammar fit"),
                recommendation(scenario.vocabularyCourseId(), "Vocabulary fit")));

        mockMvc.perform(post("/api/student/course-recommendations/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxItems\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].courseId").value(scenario.grammarCourseId()))
                .andExpect(jsonPath("$.items[0].reason").value("Grammar fit"));
    }

    @Test
    void generateCourseRecommendationsRejectsWhenNoPlacementOrEnoughHistory() throws Exception {
        StudentScenario scenario = createStudentScenario();

        mockMvc.perform(post("/api/student/course-recommendations/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxItems\":8}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generateCourseRecommendationsTwiceLeavesOneActivePathAndArchivesPrevious() throws Exception {
        CourseRecommendationScenario scenario = createSubmittedPlacementAndApprovedCoursesScenario();
        mockAiCourseRecommendations(List.of(recommendation(scenario.grammarCourseId(), "Grammar fit")));

        mockMvc.perform(post("/api/student/course-recommendations/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxItems\":8}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/student/course-recommendations/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxItems\":8}"))
                .andExpect(status().isOk());

        assertThat(courseRecommendationPathRepository.findByUserAndStatus(scenario.student(), LearningPathStatus.ACTIVE))
                .hasSize(1);
        assertThat(courseRecommendationPathRepository.findByUserAndStatus(scenario.student(), LearningPathStatus.ARCHIVED))
                .hasSize(1);
    }

    @Test
    void databaseRejectsMultipleActiveCourseRecommendationPathsForSameStudent() throws Exception {
        StudentScenario scenario = createStudentScenario();
        courseRecommendationPathRepository.saveAndFlush(recommendationPath(scenario.student(), LearningPathStatus.ACTIVE));

        assertThrows(DataIntegrityViolationException.class, () ->
                courseRecommendationPathRepository.saveAndFlush(recommendationPath(scenario.student(), LearningPathStatus.ACTIVE)));
    }

    @Test
    void generateCourseRecommendationsReturnsTooManyRequestsAtFreeQuotaAndDoesNotCallAi() throws Exception {
        CourseRecommendationScenario scenario = createSubmittedPlacementAndApprovedCoursesScenario();
        fillFreeQuota(scenario.student(), AiUsageFeature.COURSE_RECOMMENDATION);

        mockMvc.perform(post("/api/student/course-recommendations/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxItems\":8}"))
                .andExpect(status().isTooManyRequests());

        verify(aiLearningPathClient, never()).generateCourseRecommendations(any(AiCourseRecommendationRequest.class));
    }

    private CourseRecommendationScenario createSubmittedPlacementAndApprovedCoursesScenario() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        StudentScenario student = createStudentScenario();
        long grammarCourseId = createApprovedCourse(teacher, "Grammar Course", CourseType.GRAMMAR, false);
        long vocabularyCourseId = createApprovedCourse(teacher, "Vocabulary Course", CourseType.VOCABULARY, false);
        createApprovedCourse(teacher, "Placement Only Course", CourseType.GRAMMAR, true);
        createSubmittedPlacement(student.student(), teacher);
        return new CourseRecommendationScenario(student.student(), student.studentToken(), grammarCourseId, vocabularyCourseId);
    }

    private StudentScenario createStudentScenario() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        return new StudentScenario(student, loginAndGetAccessToken(student.getEmail()));
    }

    private void createSubmittedPlacement(User student, User teacher) {
        Course course = courseRepository.save(course(teacher, "Placement", CourseType.GRAMMAR));
        Topic topic = topicRepository.save(topic(course, "Placement Topic"));
        Lesson lesson = lessonRepository.save(lesson(topic, teacher, "Placement Lesson", true));
        Question question = questionRepository.save(question(lesson, teacher, "grammar"));
        QuestionOption option = questionOptionRepository.save(option(question, true));
        PlacementAttempt attempt = new PlacementAttempt();
        attempt.setUser(student);
        attempt.setStatus(PlacementAttemptStatus.SUBMITTED);
        attempt.setStartedAt(Instant.parse("2026-01-01T00:00:00Z"));
        attempt.setSubmittedAt(Instant.parse("2026-01-01T00:10:00Z"));
        attempt.setExpiresAt(Instant.parse("2026-01-01T00:20:00Z"));
        attempt.setScorePercent(0);
        attempt = placementAttemptRepository.save(attempt);
        PlacementAttemptQuestion attemptQuestion = new PlacementAttemptQuestion();
        attemptQuestion.setPlacementAttempt(attempt);
        attemptQuestion.setQuestion(question);
        attemptQuestion.setSequenceOrder(1);
        attemptQuestion.setSkill("GRAMMAR");
        placementAttemptQuestionRepository.save(attemptQuestion);
        PlacementAttemptAnswer answer = new PlacementAttemptAnswer();
        answer.setPlacementAttempt(attempt);
        answer.setQuestion(question);
        answer.setSelectedOption(option);
        answer.setCorrect(false);
        answer.setScorePercent(0);
        placementAttemptAnswerRepository.save(answer);
    }

    private long createApprovedCourse(User teacher, String name, CourseType type, boolean placementOnly) {
        Course course = courseRepository.save(course(teacher, name, type));
        Topic topic = topicRepository.save(topic(course, name + " Topic"));
        lessonRepository.save(lesson(topic, teacher, name + " Lesson", placementOnly));
        return course.getId();
    }

    private Course course(User teacher, String name, CourseType type) {
        Course course = new Course();
        course.setTeacher(teacher);
        course.setName(name);
        course.setDescription(name + " description");
        course.setType(type);
        course.setLevel("A1");
        course.setOrderIndex(1);
        course.setStatus(ContentStatus.APPROVED);
        course.setPublishedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return course;
    }

    private Topic topic(Course course, String name) {
        Topic topic = new Topic();
        topic.setCourse(course);
        topic.setName(name);
        topic.setOrderIndex(1);
        return topic;
    }

    private Lesson lesson(Topic topic, User teacher, String title, boolean placementTest) {
        Lesson lesson = new Lesson();
        lesson.setTopic(topic);
        lesson.setTeacher(teacher);
        lesson.setTitle(title);
        lesson.setDescription(title + " description");
        lesson.setContent(title + " content");
        lesson.setSkillType(SkillType.GRAMMAR);
        lesson.setOrderIndex(1);
        lesson.setXpReward(10);
        lesson.setPlacementTest(placementTest);
        lesson.setStatus(ContentStatus.APPROVED);
        return lesson;
    }

    private Question question(Lesson lesson, User teacher, String knowledgePoint) {
        Question question = new Question();
        question.setLesson(lesson);
        question.setTeacher(teacher);
        question.setType(QuestionType.MULTIPLE_CHOICE);
        question.setQuestionText("Choose the correct answer");
        question.setCorrectAnswer("Correct");
        question.setKnowledgePoint(knowledgePoint);
        question.setOrderIndex(1);
        question.setDifficulty("EASY");
        question.setXpReward(5);
        question.setStatus(ContentStatus.APPROVED);
        return question;
    }

    private QuestionOption option(Question question, boolean correct) {
        QuestionOption option = new QuestionOption();
        option.setQuestion(question);
        option.setOptionText(correct ? "Correct" : "Wrong");
        option.setCorrect(correct);
        option.setOrderIndex(1);
        return option;
    }

    private void mockAiCourseRecommendations(List<AiCourseRecommendationResponse.RecommendationPayload> recommendations) {
        when(aiLearningPathClient.generateCourseRecommendations(any(AiCourseRecommendationRequest.class)))
                .thenReturn(new AiCourseRecommendationResponse(List.of("grammar"), recommendations));
    }

    private AiCourseRecommendationResponse.RecommendationPayload recommendation(long courseId, String reason) {
        return new AiCourseRecommendationResponse.RecommendationPayload(courseId, new BigDecimal("0.9000"), reason);
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

    private CourseRecommendationPath recommendationPath(User student, LearningPathStatus status) {
        CourseRecommendationPath path = new CourseRecommendationPath();
        path.setUser(student);
        path.setStatus(status);
        path.setSource(LearningPathSource.AI_SERVICE);
        path.setGeneratedAt(Instant.now());
        path.setWeaknessesJson("[]");
        return path;
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

    private void fillFreeQuota(User user, AiUsageFeature feature) {
        for (int i = 0; i < 5; i++) {
            AiUsageEvent event = new AiUsageEvent();
            event.setUser(user);
            event.setFeature(feature);
            event.setUsedAt(Instant.now());
            aiUsageEventRepository.save(event);
        }
    }

    private record StudentScenario(User student, String studentToken) {}

    private record CourseRecommendationScenario(User student, String studentToken, long grammarCourseId, long vocabularyCourseId) {}
}
