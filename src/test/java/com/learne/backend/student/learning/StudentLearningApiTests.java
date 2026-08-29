package com.learne.backend.student.learning;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.enums.LessonProgressStatus;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.LessonProgressRepository;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.QuestionOptionRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
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
class StudentLearningApiTests {
    @Autowired private UserRepository userRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuestionOptionRepository questionOptionRepository;
    @Autowired private CourseEnrollmentRepository courseEnrollmentRepository;
    @Autowired private LessonProgressRepository lessonProgressRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lessonProgressRepository.deleteAll();
        courseEnrollmentRepository.deleteAll();
        questionOptionRepository.deleteAll();
        questionRepository.deleteAll();
        lessonRepository.deleteAll();
        topicRepository.deleteAll();
        courseRepository.deleteAll();
        userRepository.deleteAll();
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
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].courseId").value(courseId));

        Course course = courseRepository.findById(courseId).orElseThrow();
        CourseEnrollment enrollment = courseEnrollmentRepository.findByUserAndCourse(student, course).orElseThrow();
        var lessonProgress = lessonProgressRepository.findByCourseEnrollmentOrderedForLearning(enrollment);
        assertEquals(2, lessonProgress.size());
        assertEquals(LessonProgressStatus.UNLOCKED, lessonProgress.get(0).getStatus());
        assertEquals(LessonProgressStatus.LOCKED, lessonProgress.get(1).getStatus());
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
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(approvedCourseId))
                .andExpect(jsonPath("$[0].name").value("English A1 Foundation"))
                .andExpect(jsonPath("$[0].level").value("A1"))
                .andExpect(jsonPath("$[0].teacherId").value(teacher.getId()))
                .andExpect(jsonPath("$[0].teacherName").value("Teacher User"))
                .andExpect(jsonPath("$[0].topicCount").value(1))
                .andExpect(jsonPath("$[0].lessonCount").value(2))
                .andExpect(jsonPath("$[0].questionCount").value(2))
                .andExpect(jsonPath("$[0].enrolled").value(false));

        mockMvc.perform(post("/api/student/courses/%d/enroll".formatted(approvedCourseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(approvedCourseId))
                .andExpect(jsonPath("$[0].enrolled").value(true));
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
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(teacherACourseId))
                .andExpect(jsonPath("$[0].level").value("A1"))
                .andExpect(jsonPath("$[0].teacherId").value(teacherA.getId()));

        mockMvc.perform(get("/api/student/courses")
                        .queryParam("level", "A2")
                        .queryParam("teacherId", teacherA.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
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
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
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
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
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
        long courseId = createApprovedCourseWithTwoLessons(teacherToken, adminToken);
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

    private long createApprovedCourseWithTwoLessons(String teacherToken, String adminToken) throws Exception {
        long courseId = createCourse(teacherToken);
        long topicId = createTopic(teacherToken, courseId);
        long firstLessonId = createLesson(teacherToken, topicId, "First Lesson", 1);
        long secondLessonId = createLesson(teacherToken, topicId, "Second Lesson", 2);
        createMultipleChoiceQuestion(teacherToken, firstLessonId, 1);
        createMultipleChoiceQuestion(teacherToken, secondLessonId, 2);

        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        return courseId;
    }

    private long createApprovedCourse(String teacherToken, String adminToken, String name, String level) throws Exception {
        long courseId = createCourse(teacherToken, name, level);
        long topicId = createTopic(teacherToken, courseId);
        long lessonId = createLesson(teacherToken, topicId, "Catalog Lesson", 1);
        createMultipleChoiceQuestion(teacherToken, lessonId, 1);

        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        return courseId;
    }

    private long createCourse(String teacherToken) throws Exception {
        return createCourse(teacherToken, "English A1 Foundation", "A1");
    }

    private long createCourse(String teacherToken, String name, String level) throws Exception {
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
                                }
                                """.formatted(name, level)))
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

    private long firstLessonId(long enrollmentId) {
        CourseEnrollment enrollment = courseEnrollmentRepository.findById(enrollmentId).orElseThrow();
        return lessonProgressRepository.findByCourseEnrollmentOrderedForLearning(enrollment).get(0).getLesson().getId();
    }

    private long secondLessonId(long enrollmentId) {
        CourseEnrollment enrollment = courseEnrollmentRepository.findById(enrollmentId).orElseThrow();
        return lessonProgressRepository.findByCourseEnrollmentOrderedForLearning(enrollment).get(1).getLesson().getId();
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
