package com.learne.backend.student.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import com.learne.backend.student.learning.enums.PlacementAttemptStatus;
import com.learne.backend.student.learning.repository.CourseRecommendationItemRepository;
import com.learne.backend.student.learning.repository.CourseRecommendationPathRepository;
import com.learne.backend.student.learning.entity.PlacementAttempt;
import com.learne.backend.student.learning.repository.PlacementAttemptAnswerRepository;
import com.learne.backend.student.learning.repository.PlacementAttemptQuestionRepository;
import com.learne.backend.student.learning.repository.PlacementAttemptRepository;
import com.learne.backend.student.learning.repository.PlacementPreferenceRepository;
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
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class StudentPlacementApiTests {
    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuestionOptionRepository questionOptionRepository;
    @Autowired private PlacementAttemptRepository placementAttemptRepository;
    @Autowired private PlacementAttemptQuestionRepository placementAttemptQuestionRepository;
    @Autowired private PlacementAttemptAnswerRepository placementAttemptAnswerRepository;
    @Autowired private PlacementPreferenceRepository placementPreferenceRepository;
    @Autowired private CourseRecommendationPathRepository courseRecommendationPathRepository;
    @Autowired private CourseRecommendationItemRepository courseRecommendationItemRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void flywayCreatesPlacementAndCourseRecommendationTables() {
        assertThat(tableExists("PLACEMENT_ATTEMPTS")).isTrue();
        assertThat(tableExists("PLACEMENT_ATTEMPT_QUESTIONS")).isTrue();
        assertThat(tableExists("PLACEMENT_ATTEMPT_ANSWERS")).isTrue();
        assertThat(tableExists("PLACEMENT_PREFERENCES")).isTrue();
        assertThat(tableExists("COURSE_RECOMMENDATION_PATHS")).isTrue();
        assertThat(tableExists("COURSE_RECOMMENDATION_ITEMS")).isTrue();
    }

    private boolean tableExists(String tableName) {
        Number count = (Number) entityManager.createNativeQuery("""
                select count(*)
                from information_schema.tables
                where upper(table_name) = :tableName
                """)
                .setParameter("tableName", tableName)
                .getSingleResult();
        return count.longValue() > 0;
    }

    @Test
    void statusBeforePlacementShowsRecommendationsCannotBeGenerated() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/student/placement/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placementCompleted").value(false))
                .andExpect(jsonPath("$.activeAttemptId").doesNotExist())
                .andExpect(jsonPath("$.skipped").value(false))
                .andExpect(jsonPath("$.canGenerateRecommendations").value(false));
    }

    @Test
    void skipPlacementPersistsPreferenceWithoutEnablingRecommendations() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(post("/api/student/placement/skip")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placementCompleted").value(false))
                .andExpect(jsonPath("$.activeAttemptId").doesNotExist())
                .andExpect(jsonPath("$.skipped").value(true))
                .andExpect(jsonPath("$.canGenerateRecommendations").value(false));

        assertThat(placementPreferenceRepository.findByUser(student)).isPresent();
    }

    @Test
    void startPlacementCreatesBalancedTwentyQuestionAttempt() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken(student.getEmail());
        createPlacementBank(teacher);

        MvcResult result = mockMvc.perform(post("/api/student/placement/start")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.remainingSeconds").isNumber())
                .andExpect(jsonPath("$.durationSeconds").value(900))
                .andExpect(jsonPath("$.questions", hasSize(20)))
                .andExpect(jsonPath("$.questions[0].sequenceOrder").value(1))
                .andExpect(jsonPath("$.questions[19].sequenceOrder").value(20))
                .andExpect(jsonPath("$.questions[0].options", hasSize(2)))
                .andReturn();

        JsonNode questions = objectMapper.readTree(result.getResponse().getContentAsString()).get("questions");
        Map<String, Integer> countsBySkill = new HashMap<>();
        questions.forEach(question -> countsBySkill.merge(question.get("skill").asText(), 1, Integer::sum));
        assertThat(countsBySkill).containsEntry("VOCABULARY", 5)
                .containsEntry("GRAMMAR", 5)
                .containsEntry("READING", 5)
                .containsEntry("WRITING", 5);
    }

    @Test
    void startPlacementRandomizesSelectionBeforeOrderingAttemptQuestions() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveStudentWhoseVocabularySeedDoesNotSelectFirstFive();
        String studentToken = loginAndGetAccessToken(student.getEmail());
        createPlacementBank(teacher, 10);

        MvcResult result = mockMvc.perform(post("/api/student/placement/start")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions", hasSize(20)))
                .andReturn();

        JsonNode questions = objectMapper.readTree(result.getResponse().getContentAsString()).get("questions");
        Map<String, Integer> countsBySkill = new HashMap<>();
        for (int index = 0; index < questions.size(); index++) {
            JsonNode question = questions.get(index);
            assertThat(question.get("sequenceOrder").asInt()).isEqualTo(index + 1);
            countsBySkill.merge(question.get("skill").asText(), 1, Integer::sum);
        }
        assertThat(countsBySkill).containsEntry("VOCABULARY", 5)
                .containsEntry("GRAMMAR", 5)
                .containsEntry("READING", 5)
                .containsEntry("WRITING", 5);
        assertThat(textsForSkill(questions, "VOCABULARY"))
                .isNotEqualTo(List.of("VOCABULARY question 1", "VOCABULARY question 2",
                        "VOCABULARY question 3", "VOCABULARY question 4", "VOCABULARY question 5"));
    }

    @Test
    void placementAttemptIsScopedToOwningStudent() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User owner = saveUser("owner@example.com", UserRole.STUDENT);
        User other = saveUser("other@example.com", UserRole.STUDENT);
        String ownerToken = loginAndGetAccessToken(owner.getEmail());
        String otherToken = loginAndGetAccessToken(other.getEmail());
        createPlacementBank(teacher);
        MvcResult result = mockMvc.perform(post("/api/student/placement/start")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        long attemptId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/student/placement/attempts/%d".formatted(attemptId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Placement attempt not found."));
    }

    @Test
    void placementQuestionsDoNotExposeAnswersOrOptionCorrectness() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String studentToken = loginAndGetAccessToken(student.getEmail());
        createPlacementBank(teacher);

        MvcResult result = mockMvc.perform(post("/api/student/placement/start")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"correctAnswer\"")
                .doesNotContain("\"correct\":")
                .doesNotContain("\"isCorrect\"");
    }

    @Test
    void submitPlacementStoresAnswersAndSkillScores() throws Exception {
        PlacementScenario scenario = createPlacementQuestionBankScenario();
        long attemptId = startPlacementAndGetAttemptId(scenario.studentToken());

        mockMvc.perform(post("/api/student/placement/attempts/%d/submit".formatted(attemptId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTwentyAnswerPayload(attemptId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.skillStats", hasSize(4)));

        PlacementAttempt attempt = placementAttemptRepository.findById(attemptId).orElseThrow();
        assertThat(placementAttemptAnswerRepository.findByPlacementAttempt(attempt)).hasSize(20);

        mockMvc.perform(get("/api/student/placement/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placementCompleted").value(true))
                .andExpect(jsonPath("$.canGenerateRecommendations").value(true));
    }

    @Test
    void submitPlacementWithEmptyAnswersGradesAllQuestionsIncorrect() throws Exception {
        PlacementScenario scenario = createPlacementQuestionBankScenario();
        long attemptId = startPlacementAndGetAttemptId(scenario.studentToken());

        mockMvc.perform(post("/api/student/placement/attempts/%d/submit".formatted(attemptId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.scorePercent").value(0))
                .andExpect(jsonPath("$.skillStats", hasSize(4)));

        PlacementAttempt attempt = placementAttemptRepository.findById(attemptId).orElseThrow();
        assertThat(placementAttemptAnswerRepository.findByPlacementAttempt(attempt)).hasSize(20);
    }

    @Test
    void submitPlacementRejectsExpiredAttempt() throws Exception {
        PlacementScenario scenario = createExpiredPlacementAttemptScenario();

        mockMvc.perform(post("/api/student/placement/attempts/%d/submit".formatted(scenario.attemptId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTwentyAnswerPayload(scenario.attemptId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitPlacementPersistsExpiredStatusWhenRejected() throws Exception {
        PlacementScenario scenario = createExpiredPlacementAttemptScenario();

        mockMvc.perform(post("/api/student/placement/attempts/%d/submit".formatted(scenario.attemptId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTwentyAnswerPayload(scenario.attemptId())))
                .andExpect(status().isBadRequest());

        assertThat(placementAttemptRepository.findById(scenario.attemptId()).orElseThrow().getStatus())
                .isEqualTo(PlacementAttemptStatus.EXPIRED);
    }

    @Test
    void submitPlacementRejectsSelectedOptionIdsPayload() throws Exception {
        PlacementScenario scenario = createPlacementQuestionBankScenario();
        long attemptId = startPlacementAndGetAttemptId(scenario.studentToken());

        mockMvc.perform(post("/api/student/placement/attempts/%d/submit".formatted(attemptId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twentyAnswerPayloadWithSelectedOptionIds(attemptId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitPlacementRejectsResubmitWithoutDuplicatingAnswers() throws Exception {
        PlacementScenario scenario = createPlacementQuestionBankScenario();
        long attemptId = startPlacementAndGetAttemptId(scenario.studentToken());

        mockMvc.perform(post("/api/student/placement/attempts/%d/submit".formatted(attemptId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTwentyAnswerPayload(attemptId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/student/placement/attempts/%d/submit".formatted(attemptId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scenario.studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTwentyAnswerPayload(attemptId)))
                .andExpect(status().isBadRequest());

        PlacementAttempt attempt = placementAttemptRepository.findById(attemptId).orElseThrow();
        assertThat(placementAttemptAnswerRepository.findByPlacementAttempt(attempt)).hasSize(20);
    }

    private void createPlacementBank(User teacher) {
        createPlacementBank(teacher, 5);
    }

    private void createPlacementBank(User teacher, int questionsPerSkill) {
        createPlacementQuestions(teacher, CourseType.VOCABULARY, SkillType.VOCABULARY, questionsPerSkill);
        createPlacementQuestions(teacher, CourseType.GRAMMAR, SkillType.GRAMMAR, questionsPerSkill);
        createPlacementQuestions(teacher, CourseType.READING, SkillType.READING, questionsPerSkill);
        createPlacementQuestions(teacher, CourseType.WRITING, SkillType.WRITING, questionsPerSkill);
    }

    private void createPlacementQuestions(User teacher, CourseType courseType, SkillType skillType, int questionCount) {
        Course course = new Course();
        course.setTeacher(teacher);
        course.setName(skillType.name() + " Placement Course");
        course.setDescription("Placement bank");
        course.setType(courseType);
        course.setLevel("A1");
        course.setOrderIndex(1);
        course.setStatus(ContentStatus.APPROVED);
        course = courseRepository.save(course);

        Topic topic = new Topic();
        topic.setCourse(course);
        topic.setName(skillType.name() + " Basics");
        topic.setOrderIndex(1);
        topic = topicRepository.save(topic);

        Lesson lesson = new Lesson();
        lesson.setTopic(topic);
        lesson.setTeacher(teacher);
        lesson.setTitle(skillType.name() + " Placement Lesson");
        lesson.setDescription("Placement lesson");
        lesson.setContent("Placement content");
        lesson.setSkillType(skillType);
        lesson.setOrderIndex(1);
        lesson.setXpReward(10);
        lesson.setPlacementTest(true);
        lesson.setStatus(ContentStatus.APPROVED);
        lesson = lessonRepository.save(lesson);

        for (int index = 1; index <= questionCount; index++) {
            Question question = new Question();
            question.setLesson(lesson);
            question.setTeacher(teacher);
            question.setType(QuestionType.MULTIPLE_CHOICE);
            question.setQuestionText(skillType.name() + " question " + index);
            question.setExplanation("Placement explanation");
            question.setCorrectAnswer("Correct");
            question.setKnowledgePoint(skillType.name().toLowerCase() + "-" + index);
            question.setOrderIndex(index);
            question.setDifficulty("EASY");
            question.setXpReward(5);
            question.setStatus(ContentStatus.APPROVED);
            question = questionRepository.save(question);
            questionOptionRepository.save(option(question, "Correct", true, 1));
            questionOptionRepository.save(option(question, "Wrong", false, 2));
        }
    }

    private List<String> textsForSkill(JsonNode questions, String skill) {
        List<String> texts = new ArrayList<>();
        questions.forEach(question -> {
            if (question.get("skill").asText().equals(skill)) {
                texts.add(question.get("questionText").asText());
            }
        });
        return texts;
    }

    private PlacementScenario createPlacementQuestionBankScenario() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        createPlacementBank(teacher);
        return new PlacementScenario(loginAndGetAccessToken(student.getEmail()), null);
    }

    private PlacementScenario createExpiredPlacementAttemptScenario() throws Exception {
        PlacementScenario scenario = createPlacementQuestionBankScenario();
        long attemptId = startPlacementAndGetAttemptId(scenario.studentToken());
        PlacementAttempt attempt = placementAttemptRepository.findById(attemptId).orElseThrow();
        attempt.setExpiresAt(Instant.now().minusSeconds(1));
        placementAttemptRepository.save(attempt);
        return new PlacementScenario(scenario.studentToken(), attemptId);
    }

    private long startPlacementAndGetAttemptId(String studentToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/student/placement/start")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String validTwentyAnswerPayload(long attemptId) throws Exception {
        PlacementAttempt attempt = placementAttemptRepository.findById(attemptId).orElseThrow();
        List<Map<String, Object>> answers = placementAttemptQuestionRepository
                .findByPlacementAttemptOrderBySequenceOrderAsc(attempt).stream()
                .map(attemptQuestion -> Map.of(
                        "questionId", attemptQuestion.getQuestion().getId(),
                        "selectedOptionId", (Object) correctOptionId(attemptQuestion.getQuestion())))
                .toList();
        return objectMapper.writeValueAsString(Map.of("answers", answers));
    }

    private String twentyAnswerPayloadWithSelectedOptionIds(long attemptId) throws Exception {
        PlacementAttempt attempt = placementAttemptRepository.findById(attemptId).orElseThrow();
        List<PlacementAnswerPayload> answers = new ArrayList<>();
        List<com.learne.backend.student.learning.entity.PlacementAttemptQuestion> attemptQuestions = placementAttemptQuestionRepository
                .findByPlacementAttemptOrderBySequenceOrderAsc(attempt);
        for (int index = 0; index < attemptQuestions.size(); index++) {
            Question question = attemptQuestions.get(index).getQuestion();
            Long correctOptionId = correctOptionId(question);
            List<Long> selectedOptionIds = index == 0 ? List.of(correctOptionId, wrongOptionId(question)) : null;
            answers.add(new PlacementAnswerPayload(question.getId(), correctOptionId, selectedOptionIds));
        }
        return objectMapper.writeValueAsString(Map.of("answers", answers));
    }

    private Long correctOptionId(Question question) {
        return questionOptionRepository.findAll().stream()
                .filter(option -> option.getQuestion().getId().equals(question.getId()) && option.isCorrect())
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private Long wrongOptionId(Question question) {
        return questionOptionRepository.findAll().stream()
                .filter(option -> option.getQuestion().getId().equals(question.getId()) && !option.isCorrect())
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private QuestionOption option(Question question, String text, boolean correct, int orderIndex) {
        QuestionOption option = new QuestionOption();
        option.setQuestion(question);
        option.setOptionText(text);
        option.setCorrect(correct);
        option.setOrderIndex(orderIndex);
        return option;
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

    private User saveStudentWhoseVocabularySeedDoesNotSelectFirstFive() {
        for (int index = 1; index <= 10; index++) {
            User student = saveUser("student-randomization-" + index + "@example.com", UserRole.STUDENT);
            if (!seedSelectsFirstFiveVocabularyQuestions(student.getId())) {
                return student;
            }
        }
        throw new IllegalStateException("Could not create deterministic randomization fixture.");
    }

    private boolean seedSelectsFirstFiveVocabularyQuestions(Long studentId) {
        List<Integer> orderIndexes = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            orderIndexes.add(index);
        }
        Collections.shuffle(orderIndexes, new Random((studentId * 31) + "VOCABULARY".hashCode()));
        List<Integer> selected = new ArrayList<>(orderIndexes.subList(0, 5));
        Collections.sort(selected);
        return selected.equals(List.of(1, 2, 3, 4, 5));
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

    private record PlacementScenario(String studentToken, Long attemptId) {}

    private record PlacementAnswerPayload(Long questionId, Long selectedOptionId, List<Long> selectedOptionIds) {}
}
