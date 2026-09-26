package com.learne.backend.admin.content;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.LearningActivityRepository;
import com.learne.backend.student.learning.repository.LearningPathItemRepository;
import com.learne.backend.student.learning.repository.LearningPathRepository;
import com.learne.backend.student.learning.repository.LessonProgressRepository;
import com.learne.backend.student.learning.repository.QuestionAnswerRepository;
import com.learne.backend.student.learning.repository.QuizAttemptRepository;
import com.learne.backend.student.learning.repository.UserStatsRepository;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.QuestionOptionRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class AdminContentReviewApiTests {
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
    private QuestionAnswerRepository questionAnswerRepository;

    @Autowired
    private QuizAttemptRepository quizAttemptRepository;

    @Autowired
    private LearningActivityRepository learningActivityRepository;

    @Autowired
    private UserStatsRepository userStatsRepository;

    @Autowired
    private LessonProgressRepository lessonProgressRepository;

    @Autowired
    private CourseEnrollmentRepository courseEnrollmentRepository;

    @Autowired
    private LearningPathItemRepository learningPathItemRepository;

    @Autowired
    private LearningPathRepository learningPathRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void adminContentReviewEndpointsRequireAdminRole() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        String studentToken = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/admin/content/courses/pending"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/content/courses/1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/content/courses/1/approve"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/content/courses/1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRejectJson()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/content/courses/pending")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/content/courses/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/content/courses/1/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/content/courses/1/reject")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRejectJson()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/content/courses/pending")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/content/courses/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/content/courses/1/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/content/courses/1/reject")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRejectJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanListPendingCoursesWithTeacherInfoAndCounts() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = createSubmittedCourse(teacherToken);

        mockMvc.perform(get("/api/admin/content/courses/pending")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(courseId))
                .andExpect(jsonPath("$[0].name").value("English A1 Foundation"))
                .andExpect(jsonPath("$[0].status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$[0].teacherId").value(teacher.getId()))
                .andExpect(jsonPath("$[0].teacherName").value("Teacher User"))
                .andExpect(jsonPath("$[0].teacherEmail").value("teacher@example.com"))
                .andExpect(jsonPath("$[0].topicCount").value(1))
                .andExpect(jsonPath("$[0].lessonCount").value(1))
                .andExpect(jsonPath("$[0].questionCount").value(1))
                .andExpect(jsonPath("$[0].updatedAt", notNullValue()));
    }

    @Test
    void adminCanGetReviewCourseDetail() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = createSubmittedCourse(teacherToken);

        mockMvc.perform(get("/api/admin/content/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(courseId))
                .andExpect(jsonPath("$.teacherId").value(teacher.getId()))
                .andExpect(jsonPath("$.teacherName").value("Teacher User"))
                .andExpect(jsonPath("$.topics", hasSize(1)))
                .andExpect(jsonPath("$.topics[0].lessons", hasSize(1)))
                .andExpect(jsonPath("$.topics[0].lessons[0].questions", hasSize(1)))
                .andExpect(jsonPath("$.topics[0].lessons[0].questions[0].options", hasSize(2)));
    }

    @Test
    void adminCanApprovePendingCourseAndCascadeStatus() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = createSubmittedCourse(teacherToken);

        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedByAdminId").value(admin.getId()))
                .andExpect(jsonPath("$.reviewedAt", notNullValue()))
                .andExpect(jsonPath("$.publishedAt", notNullValue()))
                .andExpect(jsonPath("$.rejectionReason").doesNotExist())
                .andExpect(jsonPath("$.topics[0].lessons[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.topics[0].lessons[0].publishedAt", notNullValue()))
                .andExpect(jsonPath("$.topics[0].lessons[0].questions[0].status").value("APPROVED"));
    }

    @Test
    void adminCanRejectPendingCourseWithReasonAndCascadeStatus() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = createSubmittedCourse(teacherToken);

        mockMvc.perform(post("/api/admin/content/courses/%d/reject".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Nội dung bài học chưa đủ rõ ràng."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewedByAdminId").value(admin.getId()))
                .andExpect(jsonPath("$.reviewedAt", notNullValue()))
                .andExpect(jsonPath("$.publishedAt").doesNotExist())
                .andExpect(jsonPath("$.rejectionReason").value("Nội dung bài học chưa đủ rõ ràng."))
                .andExpect(jsonPath("$.topics[0].lessons[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.topics[0].lessons[0].questions[0].status").value("REJECTED"));
    }

    @Test
    void rejectRequiresReason() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = createSubmittedCourse(teacherToken);

        mockMvc.perform(post("/api/admin/content/courses/%d/reject".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "   "
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approveAndRejectRequirePendingReviewStatus() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long draftCourseId = createCourse(teacherToken);

        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(draftCourseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only pending review courses can be approved or rejected."));

        mockMvc.perform(post("/api/admin/content/courses/%d/reject".formatted(draftCourseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Không đạt yêu cầu."
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only pending review courses can be approved or rejected."));
    }

    @Test
    void approveRequiresMinimumContent() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = createCourse(teacherToken);
        setCourseStatus(courseId, ContentStatus.PENDING_REVIEW);

        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Course must have at least one topic, lesson, and question before approval."));
    }

    @Test
    void adminCannotApproveSupersededCourseRevision() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        MvcResult importResult = importWorkbook(teacherToken, validCourseImportWorkbook())
                .andExpect(status().isOk())
                .andReturn();
        long courseId = objectMapper.readTree(importResult.getResponse().getContentAsString()).get("courseId").asLong();
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());

        MvcResult pendingDetail = mockMvc.perform(get("/api/admin/content/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId", notNullValue()))
                .andExpect(jsonPath("$.revisionNumber").value(1))
                .andReturn();
        long oldRevisionId = objectMapper.readTree(pendingDetail.getResponse().getContentAsString())
                .get("revisionId").asLong();

        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        importWorkbook(teacherToken, replaceExportedQuestion(exportResult.getResponse().getContentAsByteArray()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/content/course-revisions/%d/approve".formatted(oldRevisionId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Nội dung này không còn là phiên bản mới nhất."));
    }

    @Test
    void adminCannotRejectSupersededCourseRevision() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        MvcResult importResult = importWorkbook(teacherToken, validCourseImportWorkbook())
                .andExpect(status().isOk())
                .andReturn();
        long courseId = objectMapper.readTree(importResult.getResponse().getContentAsString()).get("courseId").asLong();
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());

        MvcResult pendingDetail = mockMvc.perform(get("/api/admin/content/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        long oldRevisionId = objectMapper.readTree(pendingDetail.getResponse().getContentAsString())
                .get("revisionId").asLong();

        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        importWorkbook(teacherToken, replaceExportedQuestion(exportResult.getResponse().getContentAsByteArray()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/content/course-revisions/%d/reject".formatted(oldRevisionId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Không còn là phiên bản mới nhất."
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Nội dung này không còn là phiên bản mới nhất."));
    }

    @Test
    void rejectingNewRevisionPreservesApprovedLiveCourse() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        MvcResult importResult = importWorkbook(teacherToken, validCourseImportWorkbook())
                .andExpect(status().isOk())
                .andReturn();
        long courseId = objectMapper.readTree(importResult.getResponse().getContentAsString()).get("courseId").asLong();
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        byte[] editedWorkbook = replaceCourseMetadata(
                replaceExportedQuestion(exportResult.getResponse().getContentAsByteArray()),
                "Edited English A1", "Edited course description.");
        importWorkbook(teacherToken, editedWorkbook)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/content/courses/%d/reject".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Giữ lại phiên bản đã duyệt."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.name").value("Imported English A1"));

        org.junit.jupiter.api.Assertions.assertEquals("APPROVED", jdbcTemplate.queryForObject(
                "select status from courses where id = ?", String.class, courseId));
        org.junit.jupiter.api.Assertions.assertEquals("Imported English A1", jdbcTemplate.queryForObject(
                "select name from courses where id = ?", String.class, courseId));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from questions q join lessons l on q.lesson_id = l.id "
                        + "join topics t on l.topic_id = t.id where t.course_id = ? and q.question_text = ?",
                Integer.class, courseId, "Choose the correct greeting."));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "select count(*) from questions q join lessons l on q.lesson_id = l.id "
                        + "join topics t on l.topic_id = t.id where t.course_id = ? and q.question_text = ?",
                Integer.class, courseId, "Choose the updated greeting."));
    }

    @Test
    void coursePathApproveUsesLatestPendingRevisionWhenRevisionIdDiffersFromCourseId() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        MvcResult importResult = importWorkbook(teacherToken, validCourseImportWorkbook())
                .andExpect(status().isOk())
                .andReturn();
        long courseId = objectMapper.readTree(importResult.getResponse().getContentAsString()).get("courseId").asLong();
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        importWorkbook(teacherToken, replaceCourseMetadata(
                        replaceExportedQuestion(exportResult.getResponse().getContentAsByteArray()),
                        "Approved Revision 2", "Revision 2 description."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(2))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.name").value("Approved Revision 2"));
    }

    @Test
    void approvingEditedRevisionUpdatesLiveRowsInPlace() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        MvcResult importResult = importWorkbook(teacherToken, validCourseImportWorkbook())
                .andExpect(status().isOk())
                .andReturn();
        long courseId = objectMapper.readTree(importResult.getResponse().getContentAsString()).get("courseId").asLong();
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        Long liveLessonId = jdbcTemplate.queryForObject(
                "select l.id from lessons l join topics t on l.topic_id = t.id where t.course_id = ?",
                Long.class, courseId);
        Long liveQuestionId = jdbcTemplate.queryForObject(
                "select q.id from questions q join lessons l on q.lesson_id = l.id "
                        + "join topics t on l.topic_id = t.id where t.course_id = ?",
                Long.class, courseId);
        Long liveOptionId = jdbcTemplate.queryForObject(
                "select qo.id from question_options qo join questions q on qo.question_id = q.id "
                        + "join lessons l on q.lesson_id = l.id join topics t on l.topic_id = t.id "
                        + "where t.course_id = ? and qo.is_correct = true",
                Long.class, courseId);
        jdbcTemplate.update("insert into course_enrollments (user_id, course_id, status) values (?, ?, 'ACTIVE')",
                student.getId(), courseId);
        Long enrollmentId = jdbcTemplate.queryForObject(
                "select id from course_enrollments where user_id = ? and course_id = ?",
                Long.class, student.getId(), courseId);
        jdbcTemplate.update("insert into lesson_progress (user_id, course_enrollment_id, lesson_id, status) "
                        + "values (?, ?, ?, 'IN_PROGRESS')",
                student.getId(), enrollmentId, liveLessonId);

        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        importWorkbook(teacherToken, replaceExportedQuestion(exportResult.getResponse().getContentAsByteArray()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(2));

        org.junit.jupiter.api.Assertions.assertEquals(liveLessonId, jdbcTemplate.queryForObject(
                "select l.id from lessons l join topics t on l.topic_id = t.id where t.course_id = ? and l.status = 'APPROVED'",
                Long.class, courseId));
        org.junit.jupiter.api.Assertions.assertEquals(liveQuestionId, jdbcTemplate.queryForObject(
                "select q.id from questions q join lessons l on q.lesson_id = l.id "
                        + "join topics t on l.topic_id = t.id where t.course_id = ? and q.status = 'APPROVED'",
                Long.class, courseId));
        org.junit.jupiter.api.Assertions.assertEquals(liveOptionId, jdbcTemplate.queryForObject(
                "select qo.id from question_options qo join questions q on qo.question_id = q.id "
                        + "join lessons l on q.lesson_id = l.id join topics t on l.topic_id = t.id "
                        + "where t.course_id = ? and qo.is_correct = true",
                Long.class, courseId));
        org.junit.jupiter.api.Assertions.assertEquals("Choose the updated greeting.", jdbcTemplate.queryForObject(
                "select question_text from questions where id = ?", String.class, liveQuestionId));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from lesson_progress where lesson_id = ?", Integer.class, liveLessonId));
    }

    @Test
    void approvingEditedRevisionMarksRemovedLiveOptionsInactive() throws Exception {
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        String adminToken = loginAndGetAccessToken(admin.getEmail());
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        MvcResult importResult = importWorkbook(teacherToken, addThirdOption(validCourseImportWorkbook()))
                .andExpect(status().isOk())
                .andReturn();
        long courseId = objectMapper.readTree(importResult.getResponse().getContentAsString()).get("courseId").asLong();
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        Long removedOptionId = jdbcTemplate.queryForObject(
                "select qo.id from question_options qo join questions q on qo.question_id = q.id "
                        + "join lessons l on q.lesson_id = l.id join topics t on l.topic_id = t.id "
                        + "where t.course_id = ? and qo.option_text = ?",
                Long.class, courseId, "Orange");

        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        importWorkbook(teacherToken, removeThirdOption(exportResult.getResponse().getContentAsByteArray()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/content/courses/%d/approve".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(2));

        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from question_options where id = ?", Integer.class, removedOptionId));
        org.junit.jupiter.api.Assertions.assertFalse(jdbcTemplate.queryForObject(
                "select is_active from question_options where id = ?", Boolean.class, removedOptionId));
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
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private org.springframework.test.web.servlet.ResultActions importWorkbook(String teacherToken, byte[] workbook) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "course-import.zip",
                "application/zip",
                zipWorkbook(workbook));
        return mockMvc.perform(multipart("/api/teacher/content/import")
                .file(file)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken));
    }

    private long createSubmittedCourse(String teacherToken) throws Exception {
        long courseId = createCourse(teacherToken);
        long topicId = createTopic(teacherToken, courseId);
        long lessonId = createLesson(teacherToken, topicId);
        createQuestion(teacherToken, lessonId);
        mockMvc.perform(post("/api/teacher/content/courses/%d/submit".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
        return courseId;
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

    private long createLesson(String teacherToken, long topicId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/teacher/content/topics/%d/lessons".formatted(topicId))
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
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void createQuestion(String teacherToken, long lessonId) throws Exception {
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
                .andExpect(status().isOk());
    }

    private void setCourseStatus(long courseId, ContentStatus status) {
        courseRepository.findById(courseId).ifPresent(course -> {
            course.setStatus(status);
            courseRepository.save(course);
        });
        jdbcTemplate.update("update course_revisions set status = ? where course_id = ?", status.name(), courseId);
    }

    private String validRejectJson() {
        return """
                {
                  "reason": "Content needs clearer explanations."
                }
                """;
    }

    private byte[] validCourseImportWorkbook() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Content");
            Row header = sheet.createRow(0);
            String[] headers = {
                    "course_name", "course_description", "course_type", "course_level", "course_order_index",
                    "topic_order", "topic_title", "topic_description",
                    "lesson_order", "lesson_title", "lesson_description", "lesson_content", "skill_type", "xp_reward",
                    "placement_test", "audio_file",
                    "question_order", "question_type", "question_text", "explanation", "correct_answer",
                    "option_a", "option_b", "option_c", "option_d", "correct_option", "difficulty", "knowledge_point",
                    "_course_id", "_topic_id", "_lesson_id", "_question_id", "_option_a_id", "_option_b_id",
                    "_option_c_id", "_option_d_id", "_row_fingerprint"
            };
            for (int index = 0; index < headers.length; index++) {
                header.createCell(index).setCellValue(headers[index]);
            }

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Imported English A1");
            row.createCell(1).setCellValue("Course imported from ZIP.");
            row.createCell(2).setCellValue("COMPREHENSIVE");
            row.createCell(3).setCellValue("A1");
            row.createCell(4).setCellValue(1);
            row.createCell(5).setCellValue(1);
            row.createCell(6).setCellValue("Greetings");
            row.createCell(7).setCellValue("Basic greetings.");
            row.createCell(8).setCellValue(1);
            row.createCell(9).setCellValue("Hello");
            row.createCell(10).setCellValue("Greeting lesson.");
            row.createCell(11).setCellValue("Practice saying hello.");
            row.createCell(12).setCellValue("READING");
            row.createCell(13).setCellValue(10);
            row.createCell(14).setCellValue(false);
            row.createCell(16).setCellValue(1);
            row.createCell(17).setCellValue("MULTIPLE_CHOICE");
            row.createCell(18).setCellValue("Choose the correct greeting.");
            row.createCell(19).setCellValue("Hello is the greeting.");
            row.createCell(21).setCellValue("Hello");
            row.createCell(22).setCellValue("Apple");
            row.createCell(25).setCellValue("a");
            row.createCell(26).setCellValue("easy");
            row.createCell(27).setCellValue("greetings");
            return writeWorkbook(workbook);
        }
    }

    private byte[] replaceExportedQuestion(byte[] workbookBytes) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes))) {
            Row row = workbook.getSheet("Content").getRow(1);
            row.getCell(18).setCellValue("Choose the updated greeting.");
            row.getCell(21).setCellValue("Hi");
            row.getCell(22).setCellValue("Banana");
            return writeWorkbook(workbook);
        }
    }

    private byte[] replaceCourseMetadata(byte[] workbookBytes, String name, String description) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes))) {
            Row row = workbook.getSheet("Content").getRow(1);
            row.getCell(0).setCellValue(name);
            row.getCell(1).setCellValue(description);
            return writeWorkbook(workbook);
        }
    }

    private byte[] addThirdOption(byte[] workbookBytes) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes))) {
            Row row = workbook.getSheet("Content").getRow(1);
            row.createCell(23).setCellValue("Orange");
            return writeWorkbook(workbook);
        }
    }

    private byte[] removeThirdOption(byte[] workbookBytes) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes))) {
            Row row = workbook.getSheet("Content").getRow(1);
            row.getCell(23).setBlank();
            return writeWorkbook(workbook);
        }
    }

    private byte[] writeWorkbook(Workbook workbook) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }

    private byte[] zipWorkbook(byte[] workbook) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("course.xlsx"));
            zip.write(workbook);
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
