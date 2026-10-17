package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.domain.auth.*;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.content.audio.dto.*;
import com.learne.backend.teacher.content.audio.service.*;
import com.learne.backend.teacher.content.enrichment.CourseKnowledgePointClient;
import com.learne.backend.teacher.content.entity.*;
import com.learne.backend.teacher.content.enums.*;
import com.learne.backend.teacher.content.importexport.security.ClamAvClient;
import com.learne.backend.teacher.content.repository.*;
import com.learne.backend.teacher.content.service.*;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = "app.cloudinary.cloud-name=test-cloud")
class LessonAudioPersistenceTests {
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired TeacherContentService content;
    @Autowired CourseRevisionRepository revisions;
    @Autowired LessonRepository lessons;
    @Autowired LessonRevisionRepository revisionLessons;
    @Autowired CourseRepository courses;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired LessonAudioReadService reads;
    @Autowired LessonAudioPersistenceService persistence;
    @Autowired LessonAudioService service;
    @MockitoBean StorageService storage;
    @MockitoBean ClamAvClient scanner;
    @MockitoBean CourseKnowledgePointClient knowledge;
    @MockitoBean CacheInvalidationService cache;
    private LessonAudioTestFixture.Draft draft;
    private TransactionTemplate tx;
    private final LessonAudioState next = new LessonAudioState("https://audio/new", "new");

    @BeforeEach
    void setup() throws Exception {
        TestDatabaseCleaner.clean(jdbc);
        tx = new TransactionTemplate(manager);
        draft = new LessonAudioTestFixture(users, passwords, content, revisions, manager, mvc).draft();
        clearInvocations(storage, scanner, cache);
    }

    @ParameterizedTest
    @EnumSource(AudioTargetKind.class)
    void replacementCommitsExactRequestedNamespaceAndMirrorWithDtoInsideTransaction(AudioTargetKind kind) {
        mutate(target -> {
            set(target, "old"); set(live(), "old");
            Question question = new Question();
            question.setLesson(live()); question.setTeacher(live().getTeacher());
            question.setType(QuestionType.SHORT_ANSWER); question.setQuestionText("What time?");
            question.setCorrectAnswer("Early"); question.setStatus(ContentStatus.DRAFT);
            question.setExplanation("Explanation"); question.setOrderIndex(1); question.setXpReward(5);
            em.persist(question);
            QuestionRevision revisionQuestion = new QuestionRevision();
            revisionQuestion.setLessonRevision(target); revisionQuestion.setTeacher(target.getTeacher());
            revisionQuestion.setSourceQuestion(question); revisionQuestion.setType(question.getType());
            revisionQuestion.setQuestionText(question.getQuestionText()); revisionQuestion.setCorrectAnswer(question.getCorrectAnswer());
            revisionQuestion.setExplanation(question.getExplanation()); revisionQuestion.setOrderIndex(1); revisionQuestion.setXpReward(5);
            em.persist(revisionQuestion);
        });
        var baseline = tx.execute(s -> kind == AudioTargetKind.LIVE
                ? com.learne.backend.teacher.content.dto.LessonResponse.from(live())
                : com.learne.backend.teacher.content.dto.LessonResponse.from(revisionLesson()));
        var outcome = new CourseWriteOutcome();
        var result = persistence.replace(draft.teacher(), token(kind), next, outcome);
        assertThat(result.response()).usingRecursiveComparison().ignoringFields("audioUrl", "audioPublicId").isEqualTo(baseline);
        assertThat(result.response().id()).isEqualTo(id(kind));
        assertThat(result.response().questions()).hasSize(1);
        assertThat(result.cleanupCandidates()).containsExactly(new LessonAudioState("old", "old"));
        assertThat(outcome.rolledBack()).isFalse();
        assertStates(next, next);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void revisionRepairsMismatchedUnpublishedPairsAndRecordsBothDistinctOverwrittenStates() {
        mutate(target -> { set(target, "draft-old"); set(live(), "live-old"); });
        var result = persistence.replace(draft.teacher(), token(AudioTargetKind.REVISION), next, new CourseWriteOutcome());
        assertThat(result.cleanupCandidates()).containsExactlyInAnyOrder(
                new LessonAudioState("draft-old", "draft-old"), new LessonAudioState("live-old", "live-old"));
        assertStates(next, next);
    }

    @Test
    void sourceLessRevisionNeverTouchesNumericLiveCollision() {
        mutate(target -> { target.setSourceLesson(null); set(target, "revision"); set(live(), "live"); });
        // The cleaner preserves identity counters; deliberately arrange a collision instead of
        // depending on test order or equal sequences across the two independent namespaces.
        if (draft.liveLessonId() != draft.revisionLessonId()) {
            jdbc.update("""
                    insert into lessons (id, topic_id, teacher_id, title, skill_type, order_index,
                        xp_reward, is_locked, is_placement_test, status, created_at, updated_at,
                        audio_url, audio_public_id)
                    select ?, topic_id, teacher_id, title, skill_type, order_index,
                        xp_reward, is_locked, is_placement_test, status, created_at, updated_at,
                        audio_url, audio_public_id from lessons where id = ?
                    """, draft.revisionLessonId(), draft.liveLessonId());
        }
        var result = persistence.replace(draft.teacher(), token(AudioTargetKind.REVISION), next, new CourseWriteOutcome());
        assertThat(result.cleanupCandidates()).containsExactly(new LessonAudioState("revision", "revision"));
        assertStates(new LessonAudioState("live", "live"), next);
        assertThat(jdbc.queryForObject("select audio_url from lessons where id = ?", String.class, draft.revisionLessonId()))
                .isEqualTo("live");
    }

    @Test
    void publishedCoursePreservesLiveAndReturnsActualRejectedRevisionStatus() {
        mutate(target -> {
            set(target, "draft"); set(live(), "published");
            courses.findById(draft.courseId()).orElseThrow().setPublishedAt(Instant.now());
            revisions.findById(draft.revisionId()).orElseThrow().setStatus(CourseRevisionStatus.REJECTED);
        });
        var result = persistence.replace(draft.teacher(), token(AudioTargetKind.REVISION), next, new CourseWriteOutcome());
        assertThat(result.response().status()).isEqualTo(ContentStatus.REJECTED);
        assertThat(result.cleanupCandidates()).containsExactly(new LessonAudioState("draft", "draft"));
        assertStates(new LessonAudioState("published", "published"), next);
    }

    @Test
    void liveCannotRetargetToNewLatestRevisionEvenWithSameSourceAndAudio() {
        var before = token(AudioTargetKind.LIVE);
        var newerId = tx.execute(s -> newerMappedRevision().getId());
        var snapshot = databaseAudio();
        var outcome = new CourseWriteOutcome();
        rejects(() -> persistence.replace(draft.teacher(), before, next, outcome), HttpStatus.CONFLICT);
        assertThat(outcome.rolledBack()).isTrue();
        assertThat(databaseAudio()).isEqualTo(snapshot);
        assertThat(reads.preflight(draft.teacher(), AudioTargetKind.LIVE, draft.liveLessonId()).revisionId()).isEqualTo(newerId);
    }

    @Test
    void historicalRevisionIsNeitherModifiedNorOfferedAsCleanupCandidate() {
        mutate(target -> { set(target, "historical-only"); newerMappedRevision(); });
        var result = persistence.replace(draft.teacher(), token(AudioTargetKind.LIVE), next, new CourseWriteOutcome());
        assertThat(result.cleanupCandidates()).isEmpty();
        tx.executeWithoutResult(s -> {
            assertThat(LessonAudioState.from(revisionLesson())).isEqualTo(new LessonAudioState("historical-only", "historical-only"));
            var latest = revisions.findTopByCourseIdOrderByRevisionNumberDesc(draft.courseId()).orElseThrow();
            assertThat(LessonAudioState.from(latest.getTopics().getFirst().getLessons().getFirst())).isEqualTo(next);
            assertThat(LessonAudioState.from(live())).isEqualTo(next);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"requested-pair", "mirror-pair", "mirror-id", "latest", "removed", "owner", "state", "course-gone"})
    void stalePreflightAlwaysConflictsAndDoesNotOverwriteCurrentDatabase(String change) {
        var before = token(AudioTargetKind.REVISION);
        mutate(target -> {
            switch (change) {
                case "requested-pair" -> set(target, "changed");
                case "mirror-pair" -> set(live(), "changed");
                case "mirror-id" -> target.setSourceLesson(null);
                case "latest" -> {
                    CourseRevision old = revisions.findById(draft.revisionId()).orElseThrow();
                    CourseRevision newer = new CourseRevision();
                    newer.setCourse(old.getCourse()); newer.setTeacher(old.getTeacher());
                    newer.setRevisionNumber(old.getRevisionNumber() + 1); newer.setStatus(CourseRevisionStatus.DRAFT);
                    newer.setName(old.getName()); newer.setType(old.getType()); newer.setLevel(old.getLevel());
                    revisions.save(newer);
                }
                case "removed" -> target.getTopicRevision().getLessons().remove(target);
                case "owner" -> {
                    User other = new User(); other.setEmail("foreign@example.com"); other.setFullName("Foreign");
                    other.setPasswordHash("hash"); other.setRole(UserRole.TEACHER);
                    target.setTeacher(users.save(other));
                }
                case "state" -> revisions.findById(draft.revisionId()).orElseThrow().setStatus(CourseRevisionStatus.PENDING_REVIEW);
                case "course-gone" -> courses.deleteById(draft.courseId());
                default -> throw new AssertionError(change);
            }
        });
        var snapshot = databaseAudio();
        var outcome = new CourseWriteOutcome();
        rejects(() -> persistence.replace(draft.teacher(), before, next, outcome), HttpStatus.CONFLICT);
        assertThat(outcome.rolledBack()).isTrue();
        assertThat(databaseAudio()).isEqualTo(snapshot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"kind", "target", "course", "revision", "requested", "mirror-id", "mirror-pair"})
    void entireTokenIsCompared(String field) {
        // With colliding IDs and identical states, flipping kind alone can describe another valid token.
        // Distinct requested/mirror pairs make namespace selection observable.
        if (field.equals("kind")) mutate(target -> set(target, "revision-only-state"));
        var b = token(AudioTargetKind.REVISION);
        var altered = new LessonAudioToken(field.equals("kind") ? AudioTargetKind.LIVE : b.kind(),
                field.equals("target") ? 999L : b.targetId(), field.equals("course") ? 999L : b.courseId(),
                field.equals("revision") ? 999L : b.revisionId(), field.equals("requested") ? next : b.state(),
                field.equals("mirror-id") ? 999L : b.mirrorId(), field.equals("mirror-pair") ? next : b.mirrorState());
        rejects(() -> persistence.replace(draft.teacher(), altered, next, new CourseWriteOutcome()), HttpStatus.CONFLICT);
        assertStates(LessonAudioState.empty(), field.equals("kind")
                ? new LessonAudioState("revision-only-state", "revision-only-state") : LessonAudioState.empty());
    }

    @Test
    void failedFlushRollsBackBothEntitiesAndRecordsCompletion() {
        var outcome = new CourseWriteOutcome();
        var before = token(AudioTargetKind.LIVE);
        assertThatThrownBy(() -> persistence.replace(draft.teacher(), before,
                new LessonAudioState("x".repeat(1025), "new"), outcome)).isInstanceOf(RuntimeException.class);
        assertThat(outcome.rolledBack()).isTrue();
        assertStates(LessonAudioState.empty(), LessonAudioState.empty());
    }

    @ParameterizedTest
    @EnumSource(AudioTargetKind.class)
    void removeCommitsBothPairsAndIsIdempotentButStillValidatesState(AudioTargetKind kind) {
        mutate(target -> { set(target, "old"); set(live(), "old"); });
        var result = persistence.remove(draft.teacher(), kind, id(kind));
        assertThat(result.response().audioUrl()).isNull();
        assertThat(result.cleanupCandidates()).containsExactly(new LessonAudioState("old", "old"));
        assertStates(LessonAudioState.empty(), LessonAudioState.empty());
        assertThat(persistence.remove(draft.teacher(), kind, id(kind)).cleanupCandidates()).isEmpty();
        mutate(target -> revisions.findById(draft.revisionId()).orElseThrow().setStatus(CourseRevisionStatus.PENDING_REVIEW));
        rejects(() -> persistence.remove(draft.teacher(), kind, id(kind)), HttpStatus.CONFLICT);
    }

    @ParameterizedTest
    @EnumSource(AudioTargetKind.class)
    void missingAndForeignRemoveAre404(AudioTargetKind kind) {
        rejects(() -> persistence.remove(draft.teacher(), kind, 999999L), HttpStatus.NOT_FOUND);
        User foreign = new User();
        org.springframework.test.util.ReflectionTestUtils.setField(foreign, "id", 999999L);
        rejects(() -> persistence.remove(foreign, kind, id(kind)), HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"revision-only", "published", "mismatched"})
    void revisionRemoveOnlyOffersOverwrittenEntitiesForCleanup(String policy) {
        mutate(target -> {
            set(target, "revision-old"); set(live(), "live-old");
            if (policy.equals("revision-only")) target.setSourceLesson(null);
            if (policy.equals("published")) courses.findById(draft.courseId()).orElseThrow().setStatus(ContentStatus.APPROVED);
        });
        var result = persistence.remove(draft.teacher(), AudioTargetKind.REVISION, draft.revisionLessonId());
        if (policy.equals("mismatched")) {
            assertThat(result.cleanupCandidates()).containsExactlyInAnyOrder(
                    new LessonAudioState("revision-old", "revision-old"), new LessonAudioState("live-old", "live-old"));
            assertStates(LessonAudioState.empty(), LessonAudioState.empty());
        } else {
            assertThat(result.cleanupCandidates()).containsExactly(new LessonAudioState("revision-old", "revision-old"));
            assertStates(new LessonAudioState("live-old", "live-old"), LessonAudioState.empty());
        }
    }

    @Test
    void realUploadProxyKeepsScanStorageAndPostCommitOutsideTransactions() {
        byte[] mp3 = new byte[]{'I', 'D', '3', 0, 0, 0};
        doAnswer(i -> { outsideTransaction(); assertThat((byte[]) i.getArgument(1)).containsExactly(mp3); return null; })
                .when(scanner).assertClean(anyString(), any());
        when(storage.uploadLessonAudio(any())).thenAnswer(i -> {
            outsideTransaction();
            assertThat(((org.springframework.web.multipart.MultipartFile) i.getArgument(0)).getBytes()).containsExactly(mp3);
            return new StorageUploadResult(next.url(), next.publicId());
        });
        doAnswer(i -> { outsideTransaction(); throw new IllegalStateException("cache unavailable"); })
                .when(cache).evictContentAfterAudioCommit(draft.teacher().getId());
        var response = service.upload(draft.teacher(), AudioTargetKind.LIVE, draft.liveLessonId(),
                new MockMultipartFile("file", "file.mp3", "audio/mpeg", mp3));
        assertThat(response.audioUrl()).isEqualTo(next.url());
        assertStates(next, next);
        verify(storage, never()).delete(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"stale", "flush"})
    void realProxyRollbackCompensatesOnlyAfterDatabaseCompletion(String failure) {
        String suffix = failure.equals("flush") ? "x".repeat(1025) : "rollback";
        var uploaded = LessonAudioCleanupServiceTests.state(suffix);
        doAnswer(i -> { outsideTransaction(); return null; }).when(scanner).assertClean(anyString(), any());
        when(storage.uploadLessonAudio(any())).thenAnswer(i -> {
            outsideTransaction();
            if (failure.equals("stale")) mutate(target ->
                    revisions.findById(draft.revisionId()).orElseThrow().setStatus(CourseRevisionStatus.PENDING_REVIEW));
            return new StorageUploadResult(uploaded.url(), uploaded.publicId());
        });
        doAnswer(i -> {
            outsideTransaction();
            assertStates(LessonAudioState.empty(), LessonAudioState.empty());
            return null;
        }).when(storage).delete(uploaded.publicId(), com.learne.backend.storage.dto.StorageResourceType.VIDEO);
        var input = new MockMultipartFile("file", "file.mp3", "audio/mpeg", new byte[]{'I', 'D', '3', 0});
        if (failure.equals("stale")) {
            rejects(() -> service.upload(draft.teacher(), AudioTargetKind.LIVE, draft.liveLessonId(), input), HttpStatus.CONFLICT);
        } else {
            assertThatThrownBy(() -> service.upload(draft.teacher(), AudioTargetKind.LIVE, draft.liveLessonId(), input))
                    .isInstanceOf(org.hibernate.exception.DataException.class);
        }
        verify(storage).delete(uploaded.publicId(), com.learne.backend.storage.dto.StorageResourceType.VIDEO);
        verifyNoInteractions(cache);
        assertStates(LessonAudioState.empty(), LessonAudioState.empty());
    }

    @Test
    void orchestratorRejectsAmbientTransactionBeforeAnyExternalWork() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> service.upload(draft.teacher(), AudioTargetKind.LIVE,
                draft.liveLessonId(), new MockMultipartFile("file", new byte[]{1}))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(scanner, storage, cache);
    }

    @Test
    void mutationCopiesCandidateList() {
        var states = new ArrayList<>(List.of(next));
        var mutation = new LessonAudioMutation(null, 1L, states);
        states.clear();
        assertThat(mutation.cleanupCandidates()).containsExactly(next);
        assertThatThrownBy(() -> mutation.cleanupCandidates().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private long id(AudioTargetKind kind) { return kind == AudioTargetKind.LIVE ? draft.liveLessonId() : draft.revisionLessonId(); }
    private LessonAudioToken token(AudioTargetKind kind) { return reads.preflight(draft.teacher(), kind, id(kind)); }
    private Lesson live() { return lessons.findById(draft.liveLessonId()).orElseThrow(); }
    private LessonRevision revisionLesson() { return revisionLessons.findById(draft.revisionLessonId()).orElseThrow(); }
    private CourseRevision newerMappedRevision() {
        CourseRevision old = revisions.findById(draft.revisionId()).orElseThrow();
        CourseRevision newer = new CourseRevision();
        newer.setCourse(old.getCourse()); newer.setTeacher(old.getTeacher());
        newer.setRevisionNumber(old.getRevisionNumber() + 1); newer.setStatus(CourseRevisionStatus.DRAFT);
        newer.setName(old.getName()); newer.setType(old.getType()); newer.setLevel(old.getLevel());
        TopicRevision topic = new TopicRevision();
        topic.setCourseRevision(newer); topic.setName("New topic");
        newer.getTopics().add(topic);
        LessonRevision lesson = new LessonRevision();
        lesson.setTopicRevision(topic); lesson.setTeacher(old.getTeacher()); lesson.setSourceLesson(live());
        lesson.setTitle("New lesson"); lesson.setSkillType(SkillType.LISTENING);
        lesson.setAudioUrl(live().getAudioUrl()); lesson.setAudioPublicId(live().getAudioPublicId());
        topic.getLessons().add(lesson);
        return revisions.saveAndFlush(newer);
    }
    private void mutate(Consumer<LessonRevision> change) { tx.executeWithoutResult(s -> change.accept(revisionLesson())); }
    private void assertStates(LessonAudioState liveState, LessonAudioState revisionState) {
        tx.executeWithoutResult(s -> {
            assertThat(LessonAudioState.from(live())).isEqualTo(liveState);
            assertThat(LessonAudioState.from(revisionLesson())).isEqualTo(revisionState);
        });
    }
    private List<?> databaseAudio() {
        return List.of(jdbc.queryForList("select id, audio_url, audio_public_id from lessons order by id"),
                jdbc.queryForList("select id, audio_url, audio_public_id from lesson_revisions order by id"));
    }
    private static void set(Lesson lesson, String value) { lesson.setAudioUrl(value); lesson.setAudioPublicId(value); }
    private static void set(LessonRevision lesson, String value) { lesson.setAudioUrl(value); lesson.setAudioPublicId(value); }
    private static void rejects(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(status));
    }
    private static void outsideTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
}
