package com.learne.backend.teacher.content.submission;

import com.learne.backend.jobs.JobsPostgresTestSupport;
import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.service.*;
import com.learne.backend.teacher.content.dto.*;
import com.learne.backend.teacher.content.enums.*;
import com.learne.backend.teacher.content.audio.dto.*;
import com.learne.backend.teacher.content.audio.service.*;
import com.learne.backend.teacher.content.importexport.model.CourseImportModel;
import com.learne.backend.teacher.content.importexport.service.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real service proxies, no ambient transaction, native PostgreSQL. */
class SubmissionWriterProtocolIT extends JobsPostgresTestSupport {
    @Autowired TeacherContentService content;
    @Autowired SubmissionAcceptanceService acceptance;
    @Autowired LessonAudioPersistenceService audio;
    @Autowired LessonAudioReadService reads;
    @Autowired CourseImportPersistenceService imports;
    @Autowired SubmissionGuard guard;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.learne.backend.teacher.content.enrichment.CourseKnowledgePointClient ai;

    @org.junit.jupiter.api.Test
    void guardRequiresTransaction() {
        assertThatThrownBy(() -> guard.requireEditable(1L))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> writers() {
        return java.util.stream.Stream.of("course-update","course-delete","edit-revision","topic-create","topic-update","topic-delete",
                "lesson-create","lesson-update","lesson-delete","question-create","question-update","question-delete",
                "audio-remove","audio-replace","audio-preflight","import-final","import-preflight")
                .flatMap(writer -> java.util.stream.Stream.of(AudioTargetKind.LIVE,AudioTargetKind.REVISION)
                        .flatMap(kind -> java.util.stream.Stream.of("QUEUED","PROCESSING")
                                .map(state -> org.junit.jupiter.params.provider.Arguments.of(writer,kind,state))));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("writers")
    void activeSubmissionBlocksDirectWriterBeforeGraphMutation(String writer, AudioTargetKind kind, String state) throws Exception {
        long teacherId=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('writer@test.test','test','Teacher','TEACHER') returning id",Long.class);
        User teacher=new User(); org.springframework.test.util.ReflectionTestUtils.setField(teacher,"id",teacherId);
        long course=jdbc.queryForObject("insert into courses(teacher_id,name,type,level,status) values (?,'Course','COMPREHENSIVE','A1','DRAFT') returning id",Long.class,teacherId);
        long topic=jdbc.queryForObject("insert into topics(course_id,name) values (?,'Topic') returning id",Long.class,course);
        long lesson=jdbc.queryForObject("insert into lessons(topic_id,teacher_id,title,skill_type,status) values (?,?,'Lesson','READING','DRAFT') returning id",Long.class,topic,teacherId);
        long question=jdbc.queryForObject("insert into questions(lesson_id,teacher_id,type,question_text,correct_answer,status) values (?,?,'SHORT_ANSWER','Question','Answer','DRAFT') returning id",Long.class,lesson,teacherId);
        long revision=jdbc.queryForObject("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,1,'DRAFT','Course','COMPREHENSIVE','A1') returning id",Long.class,course,teacherId);
        long rt=jdbc.queryForObject("insert into topic_revisions(id,revision_id,source_topic_id,name) values (?,?,?,'Topic') returning id",Long.class,topic+1000000,revision,topic);
        long rl=jdbc.queryForObject("insert into lesson_revisions(id,topic_revision_id,teacher_id,source_lesson_id,title,skill_type) values (?,?,?,?,'Lesson','READING') returning id",Long.class,lesson+1000000,rt,teacherId,lesson);
        long rq=jdbc.queryForObject("insert into question_revisions(id,lesson_revision_id,teacher_id,type,question_text,correct_answer) values (?,?,?,'SHORT_ANSWER','Question','Answer') returning id",Long.class,question+1000000,rl,teacherId);
        long targetTopic=kind==AudioTargetKind.LIVE ? topic : rt;
        long targetLesson=kind==AudioTargetKind.LIVE ? lesson : rl;
        long targetQuestion=kind==AudioTargetKind.LIVE ? question : rq;
        var token=reads.preflight(teacher,kind,targetLesson);
        acceptance.accept(teacherId,course);
        jdbc.update("update course_submission_attempts set state=? where course_id=?",state,course);
        var model=new CourseImportModel(course,"Changed",null,"COMPREHENSIVE","A1",0,List.of());
        var request=new QuestionRequest(QuestionType.SHORT_ANSWER,"Changed",null,"Answer",null,2,"EASY",1,List.of());
        Runnable action=() -> { switch(writer) {
            case "course-update" -> content.updateCourse(teacher,course,null);
            case "course-delete" -> content.deleteCourse(teacher,course);
            case "edit-revision" -> content.createEditRevision(teacher,course);
            case "topic-create" -> content.createTopic(teacher,course,null);
            case "topic-update" -> content.updateTopic(teacher,targetTopic,null);
            case "topic-delete" -> content.deleteTopic(teacher,targetTopic);
            case "lesson-create" -> content.createLesson(teacher,targetTopic,null);
            case "lesson-update" -> content.updateLesson(teacher,targetLesson,null);
            case "lesson-delete" -> content.deleteLesson(teacher,targetLesson);
            case "question-create" -> content.createQuestion(teacher,targetLesson,request);
            case "question-update" -> content.updateQuestion(teacher,targetQuestion,request);
            case "question-delete" -> content.deleteQuestion(teacher,targetQuestion);
            case "audio-remove" -> audio.remove(teacher,kind,targetLesson);
            case "audio-replace" -> audio.replace(teacher,token,new LessonAudioState("changed","changed"),new CourseWriteOutcome());
            case "audio-preflight" -> reads.preflight(teacher,kind,targetLesson);
            case "import-final" -> imports.persist(teacher,new PreparedCourseImport(model,revision,Map.of(),List.of()),new CourseWriteOutcome());
            case "import-preflight" -> imports.preflight(teacher,model);
            default -> throw new AssertionError(writer);
        }};
        Runnable rejected=() -> assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).contains("đang");
            assertThat(e).isInstanceOfSatisfying(com.learne.backend.shared.api.CodedApiException.class,
                    coded -> assertThat(coded.getCode()).isEqualTo("COURSE_SUBMISSION_IN_PROGRESS"));
        });
        if (writer.endsWith("preflight")) rejected.run();
        else try (var holder=dataSource.getConnection(); var pool=Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try {
                int pid;
                try(var s=holder.createStatement(); var r=s.executeQuery("select pg_backend_pid()")) { r.next(); pid=r.getInt(1); }
                try(var s=holder.prepareStatement("select id from courses where id=? for update")) {
                    s.setLong(1,course); s.executeQuery().close();
                }
                var blocked=pool.submit(rejected);
                try {
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                    boolean waiting=false;
                    while(System.nanoTime()<deadline && !blocked.isDone()) {
                        waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)) and lower(query) like '%from courses%')",Boolean.class,pid));
                        if(waiting) break;
                        Thread.onSpinWait();
                    }
                    assertThat(waiting).as("%s acquires course lock before active check",writer).isTrue();
                    try(var s=holder.prepareStatement("select id from course_revisions where id=? for update nowait")) {
                        s.setLong(1,revision); s.executeQuery().close();
                    }
                } finally { holder.rollback(); }
                blocked.get(6,TimeUnit.SECONDS);
            } finally { holder.rollback(); pool.shutdownNow(); }
        }
        // The logical lock belongs to one course only; a different course stays editable.
        var other=content.createCourse(teacher,new CourseRequest("Other",null,CourseType.COMPREHENSIVE,"A1",0,CoursePricingType.FREE,null));
        content.updateCourse(teacher,other.id(),new CourseRequest("Other edited",null,CourseType.COMPREHENSIVE,"A1",1,CoursePricingType.FREE,null));
        assertThat(jdbc.queryForObject("select name from courses where id=?",String.class,course)).isEqualTo("Course");
        assertThat(jdbc.queryForObject("select count(*) from questions where id=?",Integer.class,question)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from course_revisions where id=?",String.class,revision)).isEqualTo("DRAFT");
    }
}
