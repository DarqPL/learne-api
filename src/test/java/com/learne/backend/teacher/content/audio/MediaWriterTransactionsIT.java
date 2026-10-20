package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learne.backend.teacher.content.audio.service.*;
import com.learne.backend.teacher.content.service.*;
import com.learne.backend.teacher.content.importexport.service.*;
import com.learne.backend.teacher.content.importexport.model.*;
import com.learne.backend.admin.content.service.AdminContentReviewService;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.testsupport.MediaTestDriver;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.context.annotation.Import;

@Import(MediaTestDriver.class)
class MediaWriterTransactionsIT extends MediaCleanupIT {
    @Autowired TeacherContentService content;
    @Autowired CourseImportPersistenceService imports;
    @Autowired AdminContentReviewService admin;
    @Autowired UserRepository users;
    @Autowired MediaTestDriver media;
    @Autowired LessonAudioCleanupService cleanup;
    @MockitoBean StorageService storage;
    @MockitoSpyBean MediaCleanupProducer intents;

    @org.junit.jupiter.api.Test void compensationEnqueueFailureKeepsOriginalExceptionAndNeverFallsBackToDelete() {
        var ids=fixture(); var outcome=new CourseWriteOutcome();
        var original=new IllegalStateException("original mutation failure");
        var event=UUID.randomUUID(); var assets=List.of(LessonAudioCleanupServiceTests.state("orphan"));
        var target=org.springframework.test.util.AopTestUtils.<MediaCleanupProducer>getUltimateTargetObject(intents);
        doThrow(new IllegalStateException("enqueue failure")).when(target).enqueue(eq(event),eq(ids[0]),eq(assets));
        try {
            assertThatThrownBy(()->{
                try {
                    new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(s->{outcome.observe();throw original;});
                } catch(RuntimeException failure) {
                    cleanup.compensate(event,ids[0],assets,true,outcome);
                    throw failure;
                }
            }).isSameAs(original);
            assertThat(outcome.rolledBack()).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from background_jobs where queue='MEDIA'",Integer.class)).isZero();
            verifyNoInteractions(storage);
        } finally { reset(target); }
    }

    @org.junit.jupiter.api.Test void unknownAndPostCommitFailureRetainWithoutAnyIntentOrDelete() {
        var ids=fixture(); var assets=List.of(LessonAudioCleanupServiceTests.state("orphan"));
        cleanup.compensate(UUID.randomUUID(),ids[0],assets,true,new CourseWriteOutcome());
        var outcome=new CourseWriteOutcome(); var original=new IllegalStateException("post commit failure");
        assertThatThrownBy(()->{
            try {
                new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(s->{
                    outcome.observe();
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                        @Override public void afterCommit(){throw original;}
                    });
                });
            } catch(RuntimeException failure) { cleanup.compensate(UUID.randomUUID(),ids[0],assets,true,outcome); throw failure; }
        }).isSameAs(original);
        assertThat(outcome.rolledBack()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from background_jobs where queue='MEDIA'",Integer.class)).isZero();
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @CsvSource({"json,false","json,true","import,false","import,true","publish,false","publish,true"})
    void actualDetachAndIntentCommitAtomicallyOrBothRollback(String writer,boolean fail) {
        var ids=fixture(); long course=ids[0], lesson=ids[1];
        long teacher=jdbc.queryForObject("select teacher_id from courses where id=?",Long.class,course);
        var user=users.findById(teacher).orElseThrow();
        long topic=jdbc.queryForObject("select topic_revision_id from lesson_revisions where id=?",Long.class,lesson);
        long revision=jdbc.queryForObject("select revision_id from topic_revisions where id=?",Long.class,topic);
        long question=jdbc.queryForObject("insert into question_revisions(lesson_revision_id,teacher_id,type,question_text,correct_answer) values (?,?,'SHORT_ANSWER','When?','Early') returning id",Long.class,lesson,teacher);
        var old=LessonAudioCleanupServiceTests.state("a"); var next=LessonAudioCleanupServiceTests.state("next");
        Runnable mutation;
        long live;
        if(writer.equals("publish")) {
            long liveTopic=jdbc.queryForObject("insert into topics(course_id,name) values (?,'Topic') returning id",Long.class,course);
            live=jdbc.queryForObject("insert into lessons(topic_id,teacher_id,title,skill_type,status,audio_url,audio_public_id) values (?,?,'Lesson','READING','DRAFT',?,?) returning id",Long.class,liveTopic,teacher,old.url(),old.publicId());
            jdbc.update("update lesson_revisions set source_lesson_id=?,audio_url=?,audio_public_id=? where id=?",live,next.url(),next.publicId(),lesson);
            jdbc.update("update topic_revisions set source_topic_id=? where id=?",liveTopic,topic);
            jdbc.update("update course_revisions set status='PENDING_REVIEW' where id=?",revision);
            mutation=()->admin.approveRevision(user,revision);
        } else if(writer.equals("import")) {
            live=0;
            var q=new QuestionImportModel(question,1,"SHORT_ANSWER","When?",null,"Early",null,null,0,List.of());
            var l=new LessonImportModel(lesson,1,"Imported",null,null,"READING",0,false,"audio/new.mp3",List.of(q));
            var model=new CourseImportModel(course,"Course",null,"COMPREHENSIVE","A1",0,List.of(new TopicImportModel(topic,1,"Topic",null,List.of(l))));
            var prepared=new PreparedCourseImport(model,imports.preflight(user,model),Map.of("audio/new.mp3",new com.learne.backend.storage.dto.StorageUploadResult(next.url(),next.publicId())),List.of());
            mutation=()->imports.persist(user,prepared,new CourseWriteOutcome());
        } else {
            live=0;
            jdbc.update("update courses set published_at=clock_timestamp() where id=?",course);
            mutation=()->content.deleteLesson(user,lesson);
        }
        var target=org.springframework.test.util.AopTestUtils.<MediaCleanupProducer>getUltimateTargetObject(intents);
        var unavailable=new IllegalStateException("enqueue unavailable");
        if(fail) doThrow(unavailable).when(target).enqueue(any(),eq(course),anyList());
        try {
            if(fail) {
                assertThatThrownBy(mutation::run).isSameAs(unavailable);
                assertThat(jdbc.queryForObject("select audio_public_id from lesson_revisions where id=?",String.class,lesson)).isEqualTo(writer.equals("publish")?next.publicId():old.publicId());
                if(live>0) assertThat(jdbc.queryForObject("select audio_public_id from lessons where id=?",String.class,live)).isEqualTo(old.publicId());
                assertThat(jdbc.queryForObject("select count(*) from background_jobs where queue='MEDIA'",Integer.class)).isZero();
                verify(storage,never()).delete(any(),any());
            } else {
                mutation.run();
                assertThat(jdbc.queryForObject("select count(*) from background_jobs where queue='MEDIA'",Integer.class)).isOne();
                verify(storage,never()).delete(any(),any());
                media.assertIntentAndDrain();
                verify(storage).delete(old.publicId(),com.learne.backend.storage.dto.StorageResourceType.VIDEO);
            }
        } finally { reset(target); }
    }
}
