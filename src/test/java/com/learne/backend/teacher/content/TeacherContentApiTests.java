package com.learne.backend.teacher.content;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class TeacherContentApiTests {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private LessonRepository lessonRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private QuestionOptionRepository questionOptionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        questionOptionRepository.deleteAll();
        questionRepository.deleteAll();
        lessonRepository.deleteAll();
        topicRepository.deleteAll();
        courseRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void courseEndpointsRequireTeacherRole() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        String studentToken = loginAndGetAccessToken(student.getEmail());
        String adminToken = loginAndGetAccessToken(admin.getEmail());

        mockMvc.perform(get("/api/teacher/content/courses"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/teacher/content/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/teacher/content/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherCanCreateListGetUpdateAndDeleteDraftCourse() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        MvcResult createResult = mockMvc.perform(post("/api/teacher/content/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCourseJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("English A1 Foundation"))
                .andExpect(jsonPath("$.type").value("COMPREHENSIVE"))
                .andExpect(jsonPath("$.level").value("A1"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();

        long courseId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/teacher/content/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(courseId));

        mockMvc.perform(get("/api/teacher/content/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(courseId))
                .andExpect(jsonPath("$.name").value("English A1 Foundation"));

        mockMvc.perform(put("/api/teacher/content/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "English A1 Updated",
                                  "description": "Updated description",
                                  "type": "GRAMMAR",
                                  "level": "A1",
                                  "orderIndex": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("English A1 Updated"))
                .andExpect(jsonPath("$.type").value("GRAMMAR"))
                .andExpect(jsonPath("$.orderIndex").value(2));

        mockMvc.perform(delete("/api/teacher/content/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Course deleted."));

        mockMvc.perform(get("/api/teacher/content/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void teacherCannotAccessAnotherTeachersCourse() throws Exception {
        User owner = saveUser("owner@example.com", UserRole.TEACHER);
        User other = saveUser("other@example.com", UserRole.TEACHER);
        String ownerToken = loginAndGetAccessToken(owner.getEmail());
        String otherToken = loginAndGetAccessToken(other.getEmail());
        long courseId = createCourse(ownerToken);

        mockMvc.perform(get("/api/teacher/content/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/teacher/content/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCourseJson()))
                .andExpect(status().isNotFound());
    }

    @Test
    void teacherCanManageTopicLessonQuestionInOwnDraftCourse() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = createCourse(teacherToken);

        MvcResult topicResult = mockMvc.perform(post("/api/teacher/content/courses/%d/topics".formatted(courseId))
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
                .andExpect(jsonPath("$.name").value("Greetings"))
                .andReturn();
        long topicId = objectMapper.readTree(topicResult.getResponse().getContentAsString()).get("id").asLong();

        MvcResult lessonResult = mockMvc.perform(post("/api/teacher/content/topics/%d/lessons".formatted(topicId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Hello and Goodbye",
                                  "description": "Practice greeting language",
                                  "content": "Use hello, hi, and goodbye in short conversations.",
                                  "skillType": "SPEAKING",
                                  "orderIndex": 1,
                                  "xpReward": 20,
                                  "placementTest": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Hello and Goodbye"))
                .andExpect(jsonPath("$.skillType").value("SPEAKING"))
                .andReturn();
        long lessonId = objectMapper.readTree(lessonResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/teacher/content/lessons/%d/questions".formatted(lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "MULTIPLE_CHOICE",
                                  "questionText": "Choose the correct greeting.",
                                  "explanation": "Hello is a greeting.",
                                  "knowledgePoint": "greetings",
                                  "orderIndex": 1,
                                  "difficulty": "easy",
                                  "xpReward": 5,
                                  "options": [
                                    { "optionText": "Hello", "correct": true, "orderIndex": 1 },
                                    { "optionText": "Apple", "correct": false, "orderIndex": 2 }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("MULTIPLE_CHOICE"))
                .andExpect(jsonPath("$.options", hasSize(2)))
                .andExpect(jsonPath("$.options[0].optionText").value("Hello"));

        mockMvc.perform(get("/api/teacher/content/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics", hasSize(1)))
                .andExpect(jsonPath("$.topics[0].lessons", hasSize(1)))
                .andExpect(jsonPath("$.topics[0].lessons[0].questions", hasSize(1)));
    }

    @Test
    void teacherCanUpdateAndDeleteNestedDraftContent() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = createCourse(teacherToken);
        long lessonId = createLesson(teacherToken, courseId);
        long questionId = createShortAnswerQuestion(teacherToken, lessonId);

        MvcResult detailResult = mockMvc.perform(get("/api/teacher/content/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detail = objectMapper.readTree(detailResult.getResponse().getContentAsString());
        long topicId = detail.get("topics").get(0).get("id").asLong();

        mockMvc.perform(put("/api/teacher/content/topics/%d".formatted(topicId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Updated topic",
                                  "description": "Updated topic description",
                                  "orderIndex": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated topic"))
                .andExpect(jsonPath("$.orderIndex").value(3));

        mockMvc.perform(put("/api/teacher/content/lessons/%d".formatted(lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Updated lesson",
                                  "description": "Updated lesson description",
                                  "content": "Updated lesson content",
                                  "skillType": "PRONUNCIATION",
                                  "orderIndex": 2,
                                  "xpReward": 15,
                                  "placementTest": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated lesson"))
                .andExpect(jsonPath("$.skillType").value("PRONUNCIATION"))
                .andExpect(jsonPath("$.placementTest").value(true));

        mockMvc.perform(put("/api/teacher/content/questions/%d".formatted(questionId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "PRONUNCIATION",
                                  "questionText": "Read aloud: I would like coffee.",
                                  "correctAnswer": "I would like coffee.",
                                  "knowledgePoint": "would_like",
                                  "orderIndex": 2,
                                  "difficulty": "medium",
                                  "xpReward": 8
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PRONUNCIATION"))
                .andExpect(jsonPath("$.correctAnswer").value("I would like coffee."));

        mockMvc.perform(delete("/api/teacher/content/questions/%d".formatted(questionId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Question deleted."));

        mockMvc.perform(delete("/api/teacher/content/lessons/%d".formatted(lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Lesson deleted."));

        mockMvc.perform(delete("/api/teacher/content/topics/%d".formatted(topicId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Topic deleted."));
    }

    @Test
    void questionValidationRequiresOneCorrectOptionForMultipleChoice() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long lessonId = createLesson(teacherToken);

        mockMvc.perform(post("/api/teacher/content/lessons/%d/questions".formatted(lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "MULTIPLE_CHOICE",
                                  "questionText": "Choose one.",
                                  "options": [
                                    { "optionText": "A", "correct": false, "orderIndex": 1 },
                                    { "optionText": "B", "correct": false, "orderIndex": 2 }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Multiple choice and true/false questions require exactly one correct option."));
    }

    @Test
    void submitCourseRequiresMinimumContentAndMovesToPendingReview() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long emptyCourseId = createCourse(teacherToken);

        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(emptyCourseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Course must have at least one topic, lesson, and question before review."));

        long completeCourseId = createCourse(teacherToken);
        long lessonId = createLesson(teacherToken, completeCourseId);
        createShortAnswerQuestion(teacherToken, lessonId);

        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(completeCourseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        mockMvc.perform(put("/api/teacher/content/courses/%d".formatted(completeCourseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCourseJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only draft or rejected content can be edited."));
    }

    private long createCourse(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/teacher/content/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCourseJson()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createLesson(String token) throws Exception {
        long courseId = createCourse(token);
        return createLesson(token, courseId);
    }

    private long createLesson(String token, long courseId) throws Exception {
        MvcResult topicResult = mockMvc.perform(post("/api/teacher/content/courses/%d/topics".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Topic",
                                  "description": "Topic description",
                                  "orderIndex": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long topicId = objectMapper.readTree(topicResult.getResponse().getContentAsString()).get("id").asLong();

        MvcResult lessonResult = mockMvc.perform(post("/api/teacher/content/topics/%d/lessons".formatted(topicId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Lesson",
                                  "description": "Lesson description",
                                  "content": "Lesson content",
                                  "skillType": "GRAMMAR",
                                  "orderIndex": 1,
                                  "xpReward": 10,
                                  "placementTest": false
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(lessonResult.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createShortAnswerQuestion(String token, long lessonId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/teacher/content/lessons/%d/questions".formatted(lessonId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "SHORT_ANSWER",
                                  "questionText": "Write the greeting.",
                                  "correctAnswer": "Hello",
                                  "knowledgePoint": "greetings",
                                  "orderIndex": 1,
                                  "difficulty": "easy",
                                  "xpReward": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private User saveUser(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(role.name() + " User");
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode("Password123"));
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

    private String validCourseJson() {
        return """
                {
                  "name": "English A1 Foundation",
                  "description": "Build the first English foundation.",
                  "type": "COMPREHENSIVE",
                  "level": "A1",
                  "orderIndex": 1
                }
                """;
    }
}
