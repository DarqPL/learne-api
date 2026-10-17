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
