package com.learne.backend.teacher.content.submission;

import com.learne.backend.domain.auth.*;
import com.learne.backend.jobs.JobsPostgresTestSupport;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.content.dto.*;
import com.learne.backend.teacher.content.enums.*;
import com.learne.backend.teacher.content.importexport.security.ClamAvClient;
import com.learne.backend.teacher.content.importexport.service.CourseImportExportService;
import com.learne.backend.teacher.content.service.TeacherContentService;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Draft capacity is independent of submission admission; all content writes use production services. */
class SubmissionDraftCapacityIT extends JobsPostgresTestSupport {
    @Autowired TeacherContentService content;
    @Autowired CourseImportExportService imports;
    @Autowired SubmissionAcceptanceService acceptance;
    @Autowired UserRepository users;
    @MockitoBean StorageService storage;
    @MockitoBean ClamAvClient scanner;

    private User teacher() {
        var user=new User();
        user.setEmail("capacity@test.test"); user.setPasswordHash("test");
        user.setFullName("Capacity teacher"); user.setRole(UserRole.TEACHER);
        return users.saveAndFlush(user);
    }

    @Test void realDraftAndQuestionCreationCanCrossThousandButSubmissionCannot() throws Exception {
        var teacher=teacher();
        var populated=imports.importCourse(teacher,archive(1000,null));
        assertThat(populated.status()).isEqualTo("DRAFT");
        long lesson=jdbc.queryForObject("""
                select l.id from lessons l join topics t on t.id=l.topic_id where t.course_id=?
                """,Long.class,populated.courseId());
        var created=content.createQuestion(teacher,lesson,new QuestionRequest(QuestionType.SHORT_ANSWER,
                "Question 1001 created by authoring service",null,"Answer",null,1001,null,0,List.of()));
        assertThat(created.id()).isNotNull();
        assertThat(jdbc.queryForObject("select question_text from questions where id=?",String.class,created.id()))
                .isEqualTo("Question 1001 created by authoring service");
        assertOversizedDraftSurvivesRejectedAcceptance(teacher,populated.courseId(),populated.revisionId());
        var draft=content.createCourse(teacher,new CourseRequest("Another draft","Description",
                CourseType.COMPREHENSIVE,"A1",0,CoursePricingType.FREE,null));
        assertThat(draft.id()).isNotEqualTo(populated.courseId());
        assertThat(jdbc.queryForObject("select status from courses where id=?",String.class,draft.id())).isEqualTo("DRAFT");
    }

    @Test void realArchiveImportPersistsThousandAndOneButSubmissionCannot() throws Exception {
        var teacher=teacher();
        var imported=imports.importCourse(teacher,archive(1001,null));
        assertThat(imported.status()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select count(*) from questions",Integer.class)).isEqualTo(1001);
        assertOversizedDraftSurvivesRejectedAcceptance(teacher,imported.courseId(),imported.revisionId());
    }

    private void assertOversizedDraftSurvivesRejectedAcceptance(User teacher,long course,long revision) {
        assertThat(questionCount(revision)).isEqualTo(1001);
        assertThatThrownBy(() -> acceptance.accept(teacher.getId(),course))
                .isInstanceOfSatisfying(ApiException.class,error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error).hasMessageContaining("1000");
                });
        assertThat(questionCount(revision)).isEqualTo(1001);
        assertThat(jdbc.queryForObject("select status from course_revisions where id=?",String.class,revision)).isEqualTo("DRAFT");
        for(String table:List.of("course_submission_attempts","course_submission_questions","background_jobs"))
            assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isZero();
        verifyNoInteractions(storage,scanner);
    }

    private int questionCount(long revision) {
        return jdbc.queryForObject("""
                select count(*) from question_revisions q join lesson_revisions l on l.id=q.lesson_revision_id
                join topic_revisions t on t.id=l.topic_revision_id where t.revision_id=?
                """,Integer.class,revision);
    }

    private MockMultipartFile archive(int count,Long courseId) throws Exception {
        try(var workbook=new XSSFWorkbook(); var excel=new ByteArrayOutputStream();
                var bytes=new ByteArrayOutputStream()) {
            var metadata=workbook.createSheet("_metadata");
            row(metadata,0,"_template_version","v1"); row(metadata,1,"_template_type","flat_course_content");
            var course=workbook.createSheet("Course");
            row(course,0,"course_name","Capacity draft"); row(course,1,"course_description","Description");
            row(course,2,"course_type","COMPREHENSIVE"); row(course,3,"course_level","A1");
            var sheet=workbook.createSheet("Content");
            row(sheet,0,"topic_title","topic_description","lesson_title","lesson_description","lesson_content",
                    "skill_type","audio_file","question_type","question_text","explanation","correct_answer",
                    "option_a","option_b","option_c","option_d","correct_option","_row_fingerprint",
                    "_course_id","_topic_id","_lesson_id","_question_id",
                    "_option_a_id","_option_b_id","_option_c_id","_option_d_id");
            for(int i=1;i<=count;i++)
                row(sheet,i,"Topic","Description","Lesson","Description","Reading content","READING","",
                        "SHORT_ANSWER","Question "+i,"","Answer","","","","","","",
                        courseId==null?"":courseId.toString(),"","","","","","","");
            workbook.write(excel);
            try(var zip=new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("course.xlsm")); zip.write(excel.toByteArray()); zip.closeEntry();
            }
            return new MockMultipartFile("file","capacity.zip","application/zip",bytes.toByteArray());
        }
    }

    private static void row(Sheet sheet,int index,String... values) {
        var row=sheet.createRow(index);
        for(int i=0;i<values.length;i++) row.createCell(i).setCellValue(values[i]);
    }
}
