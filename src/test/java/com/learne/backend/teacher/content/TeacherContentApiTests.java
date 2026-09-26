package com.learne.backend.teacher.content;

import static org.hamcrest.Matchers.hasSize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.QuestionOptionRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import org.springframework.mock.web.MockMultipartFile;

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
    void teacherCanDownloadCourseExcelTemplate() throws Exception {
        User teacher = saveUser("template-teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());

        mockMvc.perform(get("/api/teacher/content/course-template")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("course-template.xlsx")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty());
    }

    @Test
    void teacherCanImportNewCourseFromZip() throws Exception {
        User teacher = saveUser("import-teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "course-import.zip",
                "application/zip",
                validCourseImportZip());

        MvcResult result = mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").isNumber())
                .andExpect(jsonPath("$.revisionId").isNumber())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        long courseId = body.get("courseId").asLong();
        long revisionId = body.get("revisionId").asLong();

        Integer courseRevisionCount = jdbcTemplate.queryForObject(
                "select count(*) from course_revisions where id = ? and course_id = ? and status = 'DRAFT'",
                Integer.class,
                revisionId,
                courseId);
        Integer topicRevisionCount = jdbcTemplate.queryForObject(
                "select count(*) from topic_revisions where revision_id = ?",
                Integer.class,
                revisionId);
        Integer lessonRevisionCount = jdbcTemplate.queryForObject(
                "select count(*) from lesson_revisions lr join topic_revisions tr on lr.topic_revision_id = tr.id where tr.revision_id = ?",
                Integer.class,
                revisionId);
        Integer questionRevisionCount = jdbcTemplate.queryForObject(
                "select count(*) from question_revisions qr join lesson_revisions lr on qr.lesson_revision_id = lr.id join topic_revisions tr on lr.topic_revision_id = tr.id where tr.revision_id = ?",
                Integer.class,
                revisionId);
        Integer questionOptionRevisionCount = jdbcTemplate.queryForObject(
                "select count(*) from question_option_revisions qor join question_revisions qr on qor.question_revision_id = qr.id join lesson_revisions lr on qr.lesson_revision_id = lr.id join topic_revisions tr on lr.topic_revision_id = tr.id where tr.revision_id = ?",
                Integer.class,
                revisionId);
        Integer liveTopicCount = jdbcTemplate.queryForObject(
                "select count(*) from topics where course_id = ?",
                Integer.class,
                courseId);
        Integer liveLessonCount = jdbcTemplate.queryForObject(
                "select count(*) from lessons l join topics t on l.topic_id = t.id where t.course_id = ?",
                Integer.class,
                courseId);
        Integer liveQuestionCount = jdbcTemplate.queryForObject(
                "select count(*) from questions q join lessons l on q.lesson_id = l.id join topics t on l.topic_id = t.id where t.course_id = ?",
                Integer.class,
                courseId);
        Integer liveQuestionOptionCount = jdbcTemplate.queryForObject(
                "select count(*) from question_options qo join questions q on qo.question_id = q.id join lessons l on q.lesson_id = l.id join topics t on l.topic_id = t.id where t.course_id = ?",
                Integer.class,
                courseId);

        assertThat(courseRevisionCount).isEqualTo(1);
        assertThat(topicRevisionCount).isEqualTo(1);
        assertThat(lessonRevisionCount).isEqualTo(1);
        assertThat(questionRevisionCount).isEqualTo(1);
        assertThat(questionOptionRevisionCount).isEqualTo(2);
        assertThat(liveTopicCount).isZero();
        assertThat(liveLessonCount).isZero();
        assertThat(liveQuestionCount).isZero();
        assertThat(liveQuestionOptionCount).isZero();
    }

    @Test
    void teacherCanExportEditAndReimportCourseAsSourceOfTruth() throws Exception {
        User teacher = saveUser("reimport-teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        MockMultipartFile initialImport = new MockMultipartFile(
                "file",
                "course-import.zip",
                "application/zip",
                validCourseImportZip());

        MvcResult importResult = mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(initialImport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode importBody = objectMapper.readTree(importResult.getResponse().getContentAsString());
        long courseId = importBody.get("courseId").asLong();
        long revisionId = importBody.get("revisionId").asLong();

        Long removedQuestionId = jdbcTemplate.queryForObject(
                "select id from question_revisions where question_text = ?",
                Long.class,
                "Choose the correct greeting.");

        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("course-" + courseId + ".xlsx")))
                .andReturn();

        byte[] editedWorkbook = replaceExportedQuestion(exportResult.getResponse().getContentAsByteArray(),
                "Choose the replacement greeting.", "Hi", "Banana");
        MockMultipartFile reimport = new MockMultipartFile(
                "file",
                "course-reimport.zip",
                "application/zip",
                zipWorkbook(editedWorkbook));

        mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(reimport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId))
                .andExpect(jsonPath("$.revisionId").value(revisionId))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        Integer removedQuestionCount = jdbcTemplate.queryForObject(
                "select count(*) from question_revisions where id = ?",
                Integer.class,
                removedQuestionId);
        Integer newQuestionCount = jdbcTemplate.queryForObject(
                "select count(*) from question_revisions qr join lesson_revisions lr on qr.lesson_revision_id = lr.id "
                        + "join topic_revisions tr on lr.topic_revision_id = tr.id "
                        + "where tr.revision_id = ? and qr.question_text = ?",
                Integer.class,
                revisionId,
                "Choose the replacement greeting.");
        Integer totalQuestionCount = jdbcTemplate.queryForObject(
                "select count(*) from question_revisions qr join lesson_revisions lr on qr.lesson_revision_id = lr.id "
                        + "join topic_revisions tr on lr.topic_revision_id = tr.id where tr.revision_id = ?",
                Integer.class,
                revisionId);
        Integer liveQuestionCount = jdbcTemplate.queryForObject(
                "select count(*) from questions q join lessons l on q.lesson_id = l.id join topics t on l.topic_id = t.id where t.course_id = ?",
                Integer.class,
                courseId);

        assertThat(removedQuestionCount).isZero();
        assertThat(newQuestionCount).isEqualTo(1);
        assertThat(totalQuestionCount).isEqualTo(1);
        assertThat(liveQuestionCount).isZero();
    }

    @Test
    void teacherCanExportManuallyCreatedCourseContent() throws Exception {
        User teacher = saveUser("manual-export-teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = createCourse(teacherToken);
        long lessonId = createLesson(teacherToken, courseId);
        createShortAnswerQuestion(teacherToken, lessonId);

        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();

        try (Workbook workbook = new XSSFWorkbook(
                new java.io.ByteArrayInputStream(exportResult.getResponse().getContentAsByteArray()))) {
            Row row = workbook.getSheet("Content").getRow(1);

            assertThat(row.getCell(6).getStringCellValue()).isEqualTo("Topic");
            assertThat(row.getCell(9).getStringCellValue()).isEqualTo("Lesson");
            assertThat(row.getCell(18).getStringCellValue()).isEqualTo("Write the greeting.");
            assertThat(row.getCell(20).getStringCellValue()).isEqualTo("Hello");
            assertThat(row.getCell(27).getStringCellValue()).isEqualTo("greetings");
        }
    }

    @Test
    void teacherCanReimportApprovedOnlyCourseIntoNewDraftRevision() throws Exception {
        User teacher = saveUser("approved-reimport-teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        MockMultipartFile initialImport = new MockMultipartFile(
                "file",
                "course-import.zip",
                "application/zip",
                validCourseImportZip());

        MvcResult importResult = mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(initialImport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode importBody = objectMapper.readTree(importResult.getResponse().getContentAsString());
        long courseId = importBody.get("courseId").asLong();
        long approvedRevisionId = importBody.get("revisionId").asLong();
        jdbcTemplate.update("update courses set status = 'APPROVED' where id = ?", courseId);
        jdbcTemplate.update("update course_revisions set status = 'APPROVED' where id = ?", approvedRevisionId);

        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        byte[] editedWorkbook = replaceExportedQuestion(exportResult.getResponse().getContentAsByteArray(),
                "Choose the draft-only greeting.", "Hi", "Banana");
        editedWorkbook = replaceCourseMetadata(editedWorkbook, "Draft Revision Name", "Draft revision description.");
        MockMultipartFile reimport = new MockMultipartFile(
                "file",
                "course-reimport.zip",
                "application/zip",
                zipWorkbook(editedWorkbook));

        MvcResult reimportResult = mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(reimport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        long draftRevisionId = objectMapper.readTree(reimportResult.getResponse().getContentAsString())
                .get("revisionId").asLong();

        Integer approvedOldQuestionCount = jdbcTemplate.queryForObject(
                "select count(*) from question_revisions qr join lesson_revisions lr on qr.lesson_revision_id = lr.id "
                        + "join topic_revisions tr on lr.topic_revision_id = tr.id "
                        + "where tr.revision_id = ? and qr.question_text = ?",
                Integer.class,
                approvedRevisionId,
                "Choose the correct greeting.");
        Integer approvedNewQuestionCount = jdbcTemplate.queryForObject(
                "select count(*) from question_revisions qr join lesson_revisions lr on qr.lesson_revision_id = lr.id "
                        + "join topic_revisions tr on lr.topic_revision_id = tr.id "
                        + "where tr.revision_id = ? and qr.question_text = ?",
                Integer.class,
                approvedRevisionId,
                "Choose the draft-only greeting.");
        Integer draftRevisionNumber = jdbcTemplate.queryForObject(
                "select revision_number from course_revisions where id = ? and course_id = ? and status = 'DRAFT'",
                Integer.class,
                draftRevisionId,
                courseId);
        Integer draftNewQuestionCount = jdbcTemplate.queryForObject(
                "select count(*) from question_revisions qr join lesson_revisions lr on qr.lesson_revision_id = lr.id "
                        + "join topic_revisions tr on lr.topic_revision_id = tr.id "
                        + "where tr.revision_id = ? and qr.question_text = ?",
                Integer.class,
                draftRevisionId,
                "Choose the draft-only greeting.");
        String liveCourseName = jdbcTemplate.queryForObject(
                "select name from courses where id = ?",
                String.class,
                courseId);
        String draftRevisionName = jdbcTemplate.queryForObject(
                "select name from course_revisions where id = ?",
                String.class,
                draftRevisionId);
        String approvedRevisionName = jdbcTemplate.queryForObject(
                "select name from course_revisions where id = ?",
                String.class,
                approvedRevisionId);

        assertThat(draftRevisionId).isNotEqualTo(approvedRevisionId);
        assertThat(draftRevisionNumber).isEqualTo(2);
        assertThat(approvedOldQuestionCount).isEqualTo(1);
        assertThat(approvedNewQuestionCount).isZero();
        assertThat(draftNewQuestionCount).isEqualTo(1);
        assertThat(liveCourseName).isEqualTo("Imported English A1");
        assertThat(approvedRevisionName).isEqualTo("Imported English A1");
        assertThat(draftRevisionName).isEqualTo("Draft Revision Name");

        mockMvc.perform(get("/api/teacher/content/courses/%d".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.latestRevisionId").value(draftRevisionId))
                .andExpect(jsonPath("$.latestRevisionStatus").value("DRAFT"))
                .andExpect(jsonPath("$.hasEditableRevision").value(true));

        mockMvc.perform(get("/api/teacher/content/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].latestRevisionId").value(draftRevisionId))
                .andExpect(jsonPath("$[0].latestRevisionStatus").value("DRAFT"))
                .andExpect(jsonPath("$[0].hasEditableRevision").value(true));
    }

    @Test
    void teacherCannotReimportWorkbookWithMismatchedCourseHiddenIdRows() throws Exception {
        User teacher = saveUser("tamper-reimport-teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        MockMultipartFile initialImport = new MockMultipartFile(
                "file",
                "course-import.zip",
                "application/zip",
                validCourseImportZip());

        MvcResult importResult = mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(initialImport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        long courseId = objectMapper.readTree(importResult.getResponse().getContentAsString()).get("courseId").asLong();

        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        MockMultipartFile reimport = new MockMultipartFile(
                "file",
                "course-reimport.zip",
                "application/zip",
                zipWorkbook(duplicateExportRowWithDifferentCourseId(exportResult.getResponse().getContentAsByteArray(), courseId + 999)));

        mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(reimport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teacherCannotReimportLessonHiddenIdWithoutTopicHiddenId() throws Exception {
        User teacher = saveUser("tamper-lesson-parent-teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = importCourseAndReturnId(teacherToken);

        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        MockMultipartFile reimport = new MockMultipartFile(
                "file",
                "course-reimport.zip",
                "application/zip",
                zipWorkbook(clearHiddenId(exportResult.getResponse().getContentAsByteArray(), 29)));

        mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(reimport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teacherCannotReimportLessonHiddenIdWithWrongTopicHiddenId() throws Exception {
        User teacher = saveUser("tamper-lesson-wrong-parent-teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = importCourseAndReturnId(teacherToken);
        byte[] twoTopicSource = sourceWorkbookWithTwoTopicRows(teacherToken, courseId);

        MockMultipartFile reimport = new MockMultipartFile(
                "file",
                "course-reimport.zip",
                "application/zip",
                zipWorkbook(copySecondRowHiddenIdToFirstRow(twoTopicSource, 29)));

        mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(reimport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teacherCannotReimportQuestionHiddenIdWithoutLessonHiddenId() throws Exception {
        User teacher = saveUser("tamper-question-parent-teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = importCourseAndReturnId(teacherToken);

        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        MockMultipartFile reimport = new MockMultipartFile(
                "file",
                "course-reimport.zip",
                "application/zip",
                zipWorkbook(clearHiddenId(exportResult.getResponse().getContentAsByteArray(), 30)));

        mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(reimport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teacherCannotReimportQuestionHiddenIdWithWrongLessonHiddenId() throws Exception {
        User teacher = saveUser("tamper-question-wrong-parent-teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = importCourseAndReturnId(teacherToken);
        byte[] twoLessonSource = sourceWorkbookWithTwoLessonRows(teacherToken, courseId);

        MockMultipartFile reimport = new MockMultipartFile(
                "file",
                "course-reimport.zip",
                "application/zip",
                zipWorkbook(copySecondRowHiddenIdToFirstRow(twoLessonSource, 30)));

        mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(reimport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teacherCannotReimportOptionHiddenIdWithoutQuestionHiddenId() throws Exception {
        User teacher = saveUser("tamper-option-parent-teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = importCourseAndReturnId(teacherToken);

        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        MockMultipartFile reimport = new MockMultipartFile(
                "file",
                "course-reimport.zip",
                "application/zip",
                zipWorkbook(clearHiddenId(exportResult.getResponse().getContentAsByteArray(), 31)));

        mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(reimport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teacherCannotReimportOptionHiddenIdWithWrongQuestionHiddenId() throws Exception {
        User teacher = saveUser("tamper-option-wrong-parent-teacher@example.com", UserRole.TEACHER);
        String teacherToken = loginAndGetAccessToken(teacher.getEmail());
        long courseId = importCourseAndReturnId(teacherToken);
        byte[] twoLessonSource = sourceWorkbookWithTwoLessonRows(teacherToken, courseId);

        MockMultipartFile reimport = new MockMultipartFile(
                "file",
                "course-reimport.zip",
                "application/zip",
                zipWorkbook(copySecondRowHiddenIdToFirstRow(twoLessonSource, 32)));

        mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(reimport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isBadRequest());
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
    void courseRevisionTablesSupportLatestDraftAndPendingReview() throws Exception {
        User teacher = saveUser("revision-teacher@example.com", UserRole.TEACHER);
        String token = loginAndGetAccessToken(teacher.getEmail());

        MvcResult result = mockMvc.perform(post("/api/teacher/content/courses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Revision Course",
                                "description", "Course with revisions",
                                "type", "GRAMMAR",
                                "level", "A1",
                                "orderIndex", 1))))
                .andExpect(status().isOk())
                .andReturn();

        long courseId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        Integer revisionCount = jdbcTemplate.queryForObject(
                "select count(*) from course_revisions where course_id = ? and status = 'DRAFT'",
                Integer.class,
                courseId);
        assertThat(revisionCount).isEqualTo(1);
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

    private long importCourseAndReturnId(String token) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "course-import.zip",
                "application/zip",
                validCourseImportZip());
        MvcResult result = mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("courseId").asLong();
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

    private byte[] validCourseImportZip() throws Exception {
        return zipWorkbook(validCourseImportWorkbook());
    }

    private byte[] zipWorkbook(byte[] workbookBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("course.xlsx"));
            zip.write(workbookBytes);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private byte[] replaceExportedQuestion(byte[] workbookBytes, String questionText, String optionA, String optionB)
            throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes))) {
            Sheet sheet = workbook.getSheet("Content");
            Row original = sheet.getRow(1);
            Row replacement = sheet.createRow(2);
            for (int index = 0; index < original.getLastCellNum(); index++) {
                Cell source = original.getCell(index);
                if (source != null) {
                    copyCell(source, replacement.createCell(index));
                }
            }
            replacement.createCell(16).setCellValue(1);
            replacement.createCell(17).setCellValue("MULTIPLE_CHOICE");
            replacement.createCell(18).setCellValue(questionText);
            replacement.createCell(19).setCellValue("Replacement explanation.");
            replacement.createCell(20).setBlank();
            replacement.createCell(21).setCellValue(optionA);
            replacement.createCell(22).setCellValue(optionB);
            replacement.createCell(23).setBlank();
            replacement.createCell(24).setBlank();
            replacement.createCell(25).setCellValue("a");
            replacement.createCell(26).setCellValue("easy");
            replacement.createCell(27).setCellValue("replacement_greetings");
            for (int hiddenColumn = 31; hiddenColumn <= 34; hiddenColumn++) {
                replacement.createCell(hiddenColumn).setBlank();
            }

            sheet.removeRow(original);
            sheet.shiftRows(2, 2, -1);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] replaceCourseMetadata(byte[] workbookBytes, String courseName, String courseDescription)
            throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes))) {
            Sheet sheet = workbook.getSheet("Content");
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row != null) {
                    row.createCell(0).setCellValue(courseName);
                    row.createCell(1).setCellValue(courseDescription);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] duplicateExportRowWithDifferentCourseId(byte[] workbookBytes, long otherCourseId) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes))) {
            Sheet sheet = workbook.getSheet("Content");
            Row original = sheet.getRow(1);
            Row duplicate = sheet.createRow(sheet.getLastRowNum() + 1);
            for (int index = 0; index < original.getLastCellNum(); index++) {
                Cell source = original.getCell(index);
                if (source != null) {
                    copyCell(source, duplicate.createCell(index));
                }
            }
            duplicate.createCell(28).setCellValue(otherCourseId);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] clearHiddenId(byte[] workbookBytes, int columnIndex) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes))) {
            Sheet sheet = workbook.getSheet("Content");
            Row row = sheet.getRow(1);
            row.createCell(columnIndex).setBlank();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] sourceWorkbookWithTwoLessonRows(String teacherToken, long courseId) throws Exception {
        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        MockMultipartFile reimport = new MockMultipartFile(
                "file",
                "course-reimport.zip",
                "application/zip",
                zipWorkbook(addSecondLessonRow(exportResult.getResponse().getContentAsByteArray())));

        mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(reimport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());

        return mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
    }

    private byte[] sourceWorkbookWithTwoTopicRows(String teacherToken, long courseId) throws Exception {
        MvcResult exportResult = mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        MockMultipartFile reimport = new MockMultipartFile(
                "file",
                "course-reimport.zip",
                "application/zip",
                zipWorkbook(addSecondTopicRow(exportResult.getResponse().getContentAsByteArray())));

        mockMvc.perform(multipart("/api/teacher/content/import")
                        .file(reimport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk());

        return mockMvc.perform(get("/api/teacher/content/courses/%d/export".formatted(courseId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
    }

    private byte[] addSecondLessonRow(byte[] workbookBytes) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes))) {
            Sheet sheet = workbook.getSheet("Content");
            Row original = sheet.getRow(1);
            Row duplicate = sheet.createRow(sheet.getLastRowNum() + 1);
            for (int index = 0; index < original.getLastCellNum(); index++) {
                Cell source = original.getCell(index);
                if (source != null) {
                    copyCell(source, duplicate.createCell(index));
                }
            }
            duplicate.createCell(8).setCellValue(2);
            duplicate.createCell(9).setCellValue("Second lesson");
            duplicate.createCell(16).setCellValue(1);
            duplicate.createCell(18).setCellValue("Choose the second greeting.");
            duplicate.createCell(21).setCellValue("Hi");
            duplicate.createCell(22).setCellValue("Orange");
            duplicate.createCell(27).setCellValue("second_greetings");
            for (int hiddenColumn = 30; hiddenColumn <= 34; hiddenColumn++) {
                duplicate.createCell(hiddenColumn).setBlank();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] addSecondTopicRow(byte[] workbookBytes) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes))) {
            Sheet sheet = workbook.getSheet("Content");
            Row original = sheet.getRow(1);
            Row duplicate = sheet.createRow(sheet.getLastRowNum() + 1);
            for (int index = 0; index < original.getLastCellNum(); index++) {
                Cell source = original.getCell(index);
                if (source != null) {
                    copyCell(source, duplicate.createCell(index));
                }
            }
            duplicate.createCell(5).setCellValue(2);
            duplicate.createCell(6).setCellValue("Second topic");
            duplicate.createCell(7).setCellValue("Second topic description.");
            duplicate.createCell(8).setCellValue(1);
            duplicate.createCell(9).setCellValue("Second topic lesson");
            duplicate.createCell(16).setCellValue(1);
            duplicate.createCell(18).setCellValue("Choose the second topic greeting.");
            duplicate.createCell(21).setCellValue("Hi");
            duplicate.createCell(22).setCellValue("Orange");
            duplicate.createCell(27).setCellValue("second_topic_greetings");
            for (int hiddenColumn = 29; hiddenColumn <= 34; hiddenColumn++) {
                duplicate.createCell(hiddenColumn).setBlank();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] copySecondRowHiddenIdToFirstRow(byte[] workbookBytes, int columnIndex) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes))) {
            Sheet sheet = workbook.getSheet("Content");
            Row first = sheet.getRow(1);
            Row second = sheet.getRow(2);
            copyCell(second.getCell(columnIndex), first.createCell(columnIndex));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void copyCell(Cell source, Cell target) {
        if (source.getCellType() == CellType.NUMERIC) {
            target.setCellValue(source.getNumericCellValue());
        } else if (source.getCellType() == CellType.BOOLEAN) {
            target.setCellValue(source.getBooleanCellValue());
        } else if (source.getCellType() == CellType.BLANK) {
            target.setBlank();
        } else {
            target.setCellValue(source.toString());
        }
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

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
