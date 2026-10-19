package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.learne.backend.teacher.content.audio.dto.*;
import com.learne.backend.teacher.content.importexport.model.*;
import com.learne.backend.teacher.content.importexport.service.*;
import com.learne.backend.teacher.content.service.CourseWriteOutcome;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class LessonAudioImportConcurrencyIT extends AudioPostgresTestSupport {
    @Autowired CourseImportPersistenceService imports;
    @Autowired CourseImportExportService facade;
    @Autowired com.learne.backend.teacher.content.submission.SubmissionAcceptanceService acceptance;

    @ParameterizedTest
    @ValueSource(strings = {"managed", "unproven", "other-course-reference"})
    void acceptanceWhileImportUploadBlockedRejectsPersistenceWithSafeCompensation(String provenance) throws Exception {
        // Export a real package, then add an audio replacement to the real workbook/parser flow.
        byte[] exported=facade.exportCourse(draft.teacher(),draft.courseId());
        byte[] workbook;
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(exported))) {
            assertThat(zip.getNextEntry()).isNotNull(); workbook=zip.readAllBytes();
        }
        var edited=new java.io.ByteArrayOutputStream();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(workbook))) {
            book.getSheet("Content").getRow(1).getCell(6,org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue("audio/new.mp3");
            book.getSheet("Lessons").getRow(1).getCell(6,org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue("audio/new.mp3");
            book.write(edited);
        }
        var bytes=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("course.xlsm")); zip.write(edited.toByteArray()); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("audio/new.mp3")); zip.write(file("new").getBytes()); zip.closeEntry();
        }
        var candidate=provenance.equals("unproven")
                ? new com.learne.backend.storage.dto.StorageUploadResult("https://example.test/audio.mp3","unproven") : uploaded("new");
        if(provenance.equals("other-course-reference")) {
            var other=new LessonAudioTestFixture(users,passwords,content,revisions,manager,mvc).draft();
            jdbc.update("update lessons set audio_url=?,audio_public_id=? where id=?",candidate.url(),candidate.publicId(),other.liveLessonId());
        }
        var before=jdbc.queryForMap("select title,audio_url,audio_public_id from lesson_revisions where id=?",draft.revisionLessonId());
        try(var gate=new Gate()) {
            when(storage.uploadLessonAudio(any())).thenAnswer(i -> {
                outsideTransaction(); gate.entered.countDown(); await(gate.release); return candidate;
            });
            var importing=workers.submit(() -> facade.importCourse(draft.teacher(),new org.springframework.mock.web.MockMultipartFile("file","import.zip","application/zip",bytes.toByteArray())));
            try {
                gate.entered();
                assertThat(acceptance.accept(draft.teacher().getId(),draft.courseId()).status()).isEqualTo("QUEUED");
                assertThat(importing.isDone()).isFalse();
            } finally { gate.close(); }
            assertThatThrownBy(() -> get(importing)).isInstanceOfSatisfying(java.util.concurrent.ExecutionException.class,
                    failure -> assertThat(failure.getCause()).isInstanceOfSatisfying(com.learne.backend.shared.api.CodedApiException.class,
                            api -> { assertThat(api.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                                assertThat(api.getCode()).isEqualTo("COURSE_SUBMISSION_IN_PROGRESS"); }));
        }
        assertThat(jdbc.queryForMap("select title,audio_url,audio_public_id from lesson_revisions where id=?",draft.revisionLessonId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from course_revisions where course_id=?",Integer.class,draft.courseId())).isEqualTo(1);
        if(provenance.equals("managed")) verify(storage).delete(candidate.publicId(),com.learne.backend.storage.dto.StorageResourceType.VIDEO);
        else verify(storage,never()).delete(any(),any());
        verifyNoInteractions(knowledge);
    }

    private PreparedCourseImport prepared(boolean retain) {
        long topic = jdbc.queryForObject("select topic_revision_id from lesson_revisions where id=?", Long.class, draft.revisionLessonId());
        long question = jdbc.queryForObject("select id from question_revisions where lesson_revision_id=?", Long.class, draft.revisionLessonId());
        var q = new QuestionImportModel(retain ? question : null, 1, "SHORT_ANSWER", "When?", "Explanation",
                "Early", "daily-routine", "EASY", 5, List.of());
        var lesson = new LessonImportModel(retain ? draft.revisionLessonId() : null, 1, "Reimported", "Fixture",
                "I wake up early.", "LISTENING", 10, false, null, List.of(q));
        var model = new CourseImportModel(draft.courseId(), "Reimported course", "Fixture", "COMPREHENSIVE", "A2", 1,
                List.of(new TopicImportModel(topic, 1, "Daily life", "Fixture", List.of(lesson))));
        return new PreparedCourseImport(model, imports.preflight(draft.teacher(), model), Map.of(), List.of());
    }

    @Test
    void omittedAudioReadsNewestLockedSourceEvenWhenImportPreflightWasBeforeUpload() throws Exception {
        seedAudio("old");
        var prepared = prepared(true);
        upload();
        courseBlocks(() -> imports.persist(draft.teacher(), prepared, new CourseWriteOutcome()));
        long current = jdbc.queryForObject("select id from lesson_revisions where source_lesson_id=?", Long.class, draft.liveLessonId());
        assertThat(current).isNotEqualTo(draft.revisionLessonId());
        assertPair("lesson_revisions", current, state("new"));
        assertPair("lessons", draft.liveLessonId(), state("new"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reimportRebuildOrReplacementWinsOldUploadConflictsAndDiscoveryFindsNewTarget(boolean retain) throws Exception {
        seedAudio("old");
        var prepared = prepared(retain);
        try (var gate = gateUpload()) {
            var uploading = workers.submit(this::upload); gate.entered();
            courseBlocks(() -> imports.persist(draft.teacher(), prepared, new CourseWriteOutcome()));
            gate.close(); conflict(uploading);
            assertThat(jdbc.queryForObject("select count(*) from lesson_revisions where id=?", Integer.class, draft.revisionLessonId())).isZero();
            long current = jdbc.queryForObject("select l.id from lesson_revisions l join topic_revisions t on t.id=l.topic_revision_id where t.revision_id=?",
                    Long.class, draft.revisionId());
            var token = reads.preflight(draft.teacher(), AudioTargetKind.REVISION, current);
            assertThat(reads.targets(draft.teacher(), draft.courseId()).lessons())
                    .extracting(com.learne.backend.teacher.content.audio.dto.CourseAudioTargetsResponse.Target::lessonRevisionId)
                    .containsExactly(current);
            assertThat(token.targetId()).isEqualTo(current);
            assertThat(token.state()).isEqualTo(retain ? state("old") : LessonAudioState.empty());
            verify(storage, never()).delete(eq(state("old").publicId()), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cleanupRacingReimportRetainsCurrentReferences(boolean importFirst) throws Exception {
        seedAudio("kept");
        var prepared = prepared(true);
        java.util.concurrent.Callable<?> importing = () -> imports.persist(draft.teacher(), prepared, new CourseWriteOutcome());
        java.util.concurrent.Callable<?> cleaning = () -> { cleanup.tryCleanup(draft.courseId(), List.of(state("kept"))); return true; };
        if (importFirst) courseRace(importing, cleaning);
        else courseRace(cleaning, importing);
        verify(storage, never()).delete(eq(state("kept").publicId()), any());
        assertThat(jdbc.queryForObject("select count(*) from lesson_revisions where audio_public_id=?", Integer.class, state("kept").publicId())).isPositive();
    }

    @Test
    void uncertainReferenceRetainsUnreferencedCandidate() throws Exception {
        jdbc.update("update lesson_revisions set audio_url='https://example.com/a/../uncertain.mp3' where id=?", draft.revisionLessonId());
        courseBlocks(() -> { cleanup.tryCleanup(draft.courseId(), List.of(state("uncertain"))); return true; });
        verify(storage, never()).delete(any(), any());
    }
}
