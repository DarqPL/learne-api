package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.storage.dto.StorageDownloadResult;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.audio.service.LessonAudioCleanupService;
import com.learne.backend.teacher.content.audio.service.LessonAudioCleanupDecisionService;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.QuestionRevision;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.enums.SkillType;
import com.learne.backend.teacher.content.importexport.security.ClamAvClient;
import com.learne.backend.teacher.content.importexport.service.CourseImportExportService;
import com.learne.backend.teacher.content.importexport.service.CourseImportPersistenceService;
import com.learne.backend.teacher.content.importexport.service.PreparedCourseImport;
import com.learne.backend.teacher.content.importexport.model.CourseImportModel;
import com.learne.backend.teacher.content.importexport.model.LessonImportModel;
import com.learne.backend.teacher.content.importexport.model.TopicImportModel;
import com.learne.backend.teacher.content.importexport.archive.CourseImportArchiveReader;
import com.learne.backend.teacher.content.importexport.template.CourseExcelTemplateParser;
import com.learne.backend.teacher.content.importexport.template.CourseExportPackageBuilder;
import com.learne.backend.teacher.content.importexport.template.ExportedAudioFile;
import com.learne.backend.teacher.content.service.CourseWriteLockService;
import com.learne.backend.teacher.content.service.CourseWriteOutcome;
import com.learne.backend.shared.api.ApiException;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import com.learne.backend.teacher.content.repository.LessonRevisionRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;

@SpringBootTest(properties = "app.cloudinary.cloud-name=test-cloud")
@ActiveProfiles("test")
class CourseImportTransactionBoundaryTests extends MediaPostgresTestSupport {
    @Autowired CourseImportExportService facade;
    @Autowired UserRepository users;
    @Autowired CourseRepository courses;
    @Autowired CourseRevisionRepository revisions;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CourseImportPersistenceService persistence;
    @Autowired CourseWriteLockService locks;
    @Autowired LessonRevisionRepository lessons;
    @Autowired LessonAudioCleanupService cleanup;
    @Autowired LessonAudioCleanupDecisionService cleanupDecision;
    @MockitoBean ClamAvClient scanner;
    @MockitoBean StorageService storage;

    @Test
    void realFacadeScansAndUploadsOutsideDatabaseTransaction() {
        Fixture fixture = fixture();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("scanner transaction").isFalse();
            return null;
        }).when(scanner).assertClean(any(), any());
        when(storage.uploadLessonAudio(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("upload transaction").isFalse();
            return new StorageUploadResult("https://cdn/new.mp3", "new");
        });

        assertThat(facade.importCourse(fixture.teacher(), fixture.file()).courseId()).isEqualTo(fixture.courseId());
        verify(scanner).assertClean(any(), any());
        verify(storage).uploadLessonAudio(any());
    }

    @Test
    void uploadTimeChildDeletionIsRejectedByFinalValidationAndLeavesDatabaseUnchanged() {
        Fixture fixture = fixture();
        LessonAudioState uploaded = managedState();
        when(storage.uploadLessonAudio(any())).thenAnswer(call -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                locks.teacherCourse(fixture.courseId(), fixture.teacher());
                lessons.deleteById(fixture.lessonId());
            });
            return new StorageUploadResult(uploaded.url(), uploaded.publicId());
        });

        assertThatThrownBy(() -> facade.importCourse(fixture.teacher(), fixture.file()))
                .isInstanceOf(ApiException.class).hasMessageContaining("ID bài học trong file không thuộc");
        assertThat(lessons.existsById(fixture.lessonId())).isFalse();
        media.assertIntentAndDrain();
        verify(storage).delete(uploaded.publicId(), StorageResourceType.VIDEO);
    }

    @Test
    void uploadTimeNewSourceRevisionIsRejected() {
        Fixture fixture = fixture();
        LessonAudioState uploaded = managedState();
        when(storage.uploadLessonAudio(any())).thenAnswer(call -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                Course course = locks.teacherCourse(fixture.courseId(), fixture.teacher());
                CourseRevision next = new CourseRevision();
                next.setCourse(course);
                next.setTeacher(fixture.teacher());
                next.setName("Newer draft");
                next.setType(CourseType.COMPREHENSIVE);
                next.setLevel("A1");
                next.setRevisionNumber(2);
                next.setStatus(CourseRevisionStatus.DRAFT);
                revisions.save(next);
            });
            return new StorageUploadResult(uploaded.url(), uploaded.publicId());
        });

        assertThatThrownBy(() -> facade.importCourse(fixture.teacher(), fixture.file()))
                .isInstanceOf(ApiException.class).hasMessageContaining("Phiên bản khóa học đã thay đổi");
        media.assertIntentAndDrain();
        verify(storage).delete(uploaded.publicId(), StorageResourceType.VIDEO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"live-id", "live-url", "history-id", "history-url", "history-url-alias", "history-url-encoded"})
    void realGlobalReferencesIncludingRejectedLiveAndSupersededUrlOnlyRevisionsRetain(String kind) {
        Fixture owner = fixture();
        Fixture otherCourse = fixture();
        LessonAudioState candidate = managedState();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Course course = locks.teacherCourse(otherCourse.courseId(), otherCourse.teacher());
            if (kind.startsWith("live")) {
                Topic topic = new Topic();
                topic.setCourse(course);
                topic.setName("Historical live topic");
                course.getTopics().add(topic);
                Lesson lesson = new Lesson();
                lesson.setTopic(topic);
                lesson.setTeacher(otherCourse.teacher());
                lesson.setTitle("Rejected reference");
                lesson.setSkillType(SkillType.LISTENING);
                lesson.setStatus(ContentStatus.REJECTED);
                lesson.setAudioPublicId(kind.endsWith("id") ? candidate.publicId() : null);
                lesson.setAudioUrl(kind.endsWith("url") ? candidate.url() : null);
                topic.getLessons().add(lesson);
            } else {
                CourseRevision revision = revisions.findTopByCourseIdOrderByRevisionNumberDesc(course.getId()).orElseThrow();
                revision.setStatus(CourseRevisionStatus.SUPERSEDED);
                LessonRevision lesson = revision.getTopics().getFirst().getLessons().getFirst();
                lesson.setAudioPublicId(kind.endsWith("id") ? candidate.publicId() : null);
                lesson.setAudioUrl(kind.endsWith("encoded") ? candidate.url().replace("/learne/", "/%6cearne/")
                        : kind.endsWith("alias") ? candidate.url().replace("/v123/", "/v456/")
                        : kind.endsWith("url") ? candidate.url() : null);
            }
        });

        assertThat(cleanupDecision.canDelete(owner.courseId(), candidate)).isFalse();
        media.enqueueAndDrain(owner.courseId(), List.of(candidate));
        verify(storage, never()).delete(any(), any());
    }

    @Test
    void providerDeleteRunsAfterDecisionTransactionAndCourseLockRelease() {
        Fixture fixture = fixture();
        LessonAudioState candidate = managedState();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                        locks.teacherCourse(fixture.courseId(), fixture.teacher()))).get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            return null;
        }).when(storage).delete(candidate.publicId(), StorageResourceType.VIDEO);

        media.enqueueAndDrain(fixture.courseId(), List.of(candidate));

        verify(storage).delete(candidate.publicId(), StorageResourceType.VIDEO);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "live,dot", "history,dot", "live,parent", "history,parent", "live,empty", "history,empty"
    })
    void ambiguousUrlOnlyLiveAndHistoricalReferencesNeverAuthorizeDeletion(String target, String alias) {
        Fixture owner = fixture();
        Fixture reference = fixture();
        LessonAudioState candidate = managedState();
        String referenceUrl = candidate.url().replace("/v123/", "/").replace("/learne/", switch (alias) {
            case "dot" -> "/learne/./";
            case "parent" -> "/learne/temporary/../";
            default -> "/learne//";
        });
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Course course = locks.teacherCourse(reference.courseId(), reference.teacher());
            if (target.equals("live")) {
                Topic topic = new Topic();
                topic.setCourse(course);
                topic.setName("URL-only reference");
                course.getTopics().add(topic);
                Lesson lesson = new Lesson();
                lesson.setTopic(topic);
                lesson.setTeacher(reference.teacher());
                lesson.setTitle("Rejected URL-only reference");
                lesson.setSkillType(SkillType.LISTENING);
                lesson.setStatus(ContentStatus.REJECTED);
                lesson.setAudioUrl(referenceUrl);
                topic.getLessons().add(lesson);
            } else {
                CourseRevision revision = revisions.findTopByCourseIdOrderByRevisionNumberDesc(course.getId()).orElseThrow();
                revision.setStatus(CourseRevisionStatus.SUPERSEDED);
                LessonRevision lesson = revision.getTopics().getFirst().getLessons().getFirst();
                lesson.setAudioPublicId(null);
                lesson.setAudioUrl(referenceUrl);
            }
        });
        try {
            // Neither the exact URL nor the literal canonical public ID occurs in this reference.
            assertThat(referenceUrl).doesNotContain("%").doesNotContain(candidate.publicId());
            media.enqueueAndDrain(owner.courseId(), List.of(candidate));
            verify(storage, never()).delete(any(), any());
            assertThat(cleanupDecision.canDelete(owner.courseId(), candidate)).isFalse();
        } finally {
            // Remove this deliberately ambiguous fixture so conservative global retention cannot pollute other tests.
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                Course course = locks.teacherCourse(reference.courseId(), reference.teacher());
                course.getTopics().forEach(topic -> topic.getLessons().forEach(lesson -> lesson.setAudioUrl(null)));
                revisions.findTopByCourseIdOrderByRevisionNumberDesc(course.getId()).orElseThrow()
                        .getTopics().forEach(topic -> topic.getLessons().forEach(lesson -> lesson.setAudioUrl(null)));
            });
        }
    }

    @Test
    void cleanupRetainsInsideAmbientTransactionAndDecisionUsesIndependentCommittedView() {
        LessonAudioState candidate = managedState();
        User teacher = teacher();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Course course = new Course();
            course.setTeacher(teacher);
            course.setName("Uncommitted course");
            course.setType(CourseType.COMPREHENSIVE);
            course.setLevel("A1");
            course.setStatus(ContentStatus.DRAFT);
            courses.saveAndFlush(course);
            assertThat(cleanupDecision.canDelete(course.getId(), candidate)).isFalse();
            cleanup.tryCleanup(course.getId(), List.of(candidate));
            status.setRollbackOnly();
        });
        verify(storage, never()).delete(any(), any());
    }

    private LessonAudioState managedState() {
        String id = "learne/course-audio/" + UUID.randomUUID();
        return new LessonAudioState("https://res.cloudinary.com/test-cloud/video/upload/v123/" + id + ".mp3", id);
    }

    @Test
    void blankAudioCopiesCurrentLockedAudioRatherThanPreflightSnapshot() {
        Fixture fixture = fixture();
        CourseImportModel model = withoutAudio(model(fixture));
        Long expected = persistence.preflight(fixture.teacher(), model);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            locks.teacherCourse(fixture.courseId(), fixture.teacher());
            LessonRevision lesson = lessons.findById(fixture.lessonId()).orElseThrow();
            lesson.setAudioUrl("https://cdn/concurrent.mp3");
            lesson.setAudioPublicId("concurrent");
        });
        CourseWriteOutcome outcome = new CourseWriteOutcome();

        persistence.persist(fixture.teacher(), new PreparedCourseImport(model, expected, Map.of(), List.of()), outcome);

        assertThat(outcome.rolledBack()).isFalse();
        assertThat(currentAudio(fixture)).isEqualTo("concurrent");
        verify(storage, never()).uploadLessonAudio(any());
    }

    @Test
    void missingPreparedAudioRollsBackAndRecordsOutcome() {
        Fixture fixture = fixture();
        CourseImportModel model = model(fixture);
        Long expected = persistence.preflight(fixture.teacher(), model);
        CourseWriteOutcome outcome = new CourseWriteOutcome();

        assertThatThrownBy(() -> persistence.persist(fixture.teacher(),
                new PreparedCourseImport(model, expected, Map.of(), List.of()), outcome))
                .isInstanceOf(ApiException.class).hasMessageContaining("audio đã chuẩn bị");

        assertThat(outcome.rolledBack()).isTrue();
        assertThat(currentAudio(fixture)).isEqualTo("old");
        verify(storage, never()).delete(any(), any());
    }

    @Test
    void realNewCourseAudioImportCommitsLiveGraphAndInitialRevisionTogether() throws Exception {
        Fixture fixture = fixture();
        var sourceArchive = new CourseImportArchiveReader().read(fixture.archive(), "course.zip");
        byte[] workbook;
        try (XSSFWorkbook excel = new XSSFWorkbook(new ByteArrayInputStream(sourceArchive.workbook()));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (var row : excel.getSheet("Content")) {
                if (row.getRowNum() > 0) {
                    for (int column = 28; column <= 35; column++) {
                        row.getCell(column, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setBlank();
                    }
                }
            }
            excel.write(output);
            workbook = output.toByteArray();
        }
        byte[] archive = new CourseExportPackageBuilder().build(1L, workbook, sourceArchive.audioFiles().values().stream()
                .map(audio -> new ExportedAudioFile(audio.path(), audio.contentType(), audio.bytes())).toList());
        when(storage.uploadLessonAudio(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new StorageUploadResult("https://cdn/new.mp3", "new");
        });

        var result = facade.importCourse(fixture.teacher(), new MockMultipartFile("file", "new.zip", "application/zip", archive));

        assertThat(result.courseId()).isNotEqualTo(fixture.courseId());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Course course = courses.findById(result.courseId()).orElseThrow();
            CourseRevision revision = revisions.findById(result.revisionId()).orElseThrow();
            var liveLesson = course.getTopics().getFirst().getLessons().getFirst();
            var revisionLesson = revision.getTopics().getFirst().getLessons().getFirst();
            assertThat(revision.getRevisionNumber()).isEqualTo(1);
            assertThat(revision.getStatus()).isEqualTo(CourseRevisionStatus.DRAFT);
            assertThat(revisionLesson.getSourceLesson().getId()).isEqualTo(liveLesson.getId());
            assertThat(liveLesson.getAudioPublicId()).isEqualTo("new");
            assertThat(revisionLesson.getAudioPublicId()).isEqualTo("new");
            assertThat(revisionLesson.getQuestions().getFirst().getSourceQuestion().getId())
                    .isEqualTo(liveLesson.getQuestions().getFirst().getId());
        });
    }

    @Test
    void ownershipIsCheckedBeforeScannerOrUpload() {
        Fixture fixture = fixture();
        User otherTeacher = teacher();

        assertThatThrownBy(() -> facade.importCourse(otherTeacher, fixture.file())).isInstanceOf(ApiException.class);

        verify(scanner, never()).assertClean(any(), any());
        verify(storage, never()).uploadLessonAudio(any());
    }

    @Test
    void preparationRejectsAnAmbientTransactionBeforeNetworkCalls() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
                facade.importCourse(fixture.teacher(), fixture.file())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verify(scanner, never()).assertClean(any(), any());
        verify(storage, never()).uploadLessonAudio(any());
    }

    @Test
    void outcomeRequiresRealTransactionAndDoesNotClassifyPostCommitFailureAsRollback() {
        CourseWriteOutcome unknown = new CourseWriteOutcome();
        assertThat(unknown.rolledBack()).isFalse();
        assertThatThrownBy(unknown::observe).isInstanceOf(IllegalStateException.class);
        CourseWriteOutcome committed = new CourseWriteOutcome();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            committed.observe();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { throw new IllegalStateException("post commit"); }
            });
        })).hasMessage("post commit");
        assertThat(committed.rolledBack()).isFalse();
        CourseWriteOutcome rolledBack = new CourseWriteOutcome();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            rolledBack.observe();
            status.setRollbackOnly();
        });
        assertThat(rolledBack.rolledBack()).isTrue();
    }

    private String currentAudio(Fixture fixture) {
        return new TransactionTemplate(transactions).execute(status -> revisions
                .findTopByCourseIdOrderByRevisionNumberDesc(fixture.courseId()).orElseThrow()
                .getTopics().getFirst().getLessons().getFirst().getAudioPublicId());
    }

    private CourseImportModel model(Fixture fixture) {
        return new CourseExcelTemplateParser().parse(new CourseImportArchiveReader()
                .read(fixture.archive(), "course.zip").workbook());
    }

    private CourseImportModel withoutAudio(CourseImportModel model) {
        return new CourseImportModel(model.courseId(), model.name(), model.description(), model.type(), model.level(),
                model.orderIndex(), model.topics().stream().map(topic -> new TopicImportModel(topic.topicId(),
                        topic.orderIndex(), topic.title(), topic.description(), topic.lessons().stream().map(lesson ->
                                new LessonImportModel(lesson.lessonId(), lesson.orderIndex(), lesson.title(), lesson.description(),
                                        lesson.content(), lesson.skillType(), lesson.xpReward(), lesson.placementTest(), null,
                                        lesson.questions())).toList())).toList());
    }

    private User teacher() {
        User teacher = new User();
        teacher.setEmail("import-" + UUID.randomUUID() + "@example.com");
        teacher.setFullName("Import teacher");
        teacher.setPasswordHash("hash");
        teacher.setRole(UserRole.TEACHER);
        users.save(teacher);
        return teacher;
    }

    private Fixture fixture() {
        User teacher = teacher();
        Long courseId = new TransactionTemplate(transactions).execute(status -> {
            Course course = new Course();
            course.setTeacher(teacher);
            course.setName("Import course");
            course.setType(CourseType.COMPREHENSIVE);
            course.setLevel("A1");
            course.setStatus(ContentStatus.DRAFT);
            courses.save(course);
            CourseRevision revision = new CourseRevision();
            revision.setCourse(course);
            revision.setTeacher(teacher);
            revision.setName("Import course");
            revision.setType(CourseType.COMPREHENSIVE);
            revision.setLevel("A1");
            revision.setRevisionNumber(1);
            revision.setStatus(CourseRevisionStatus.DRAFT);
            TopicRevision topic = new TopicRevision();
            topic.setCourseRevision(revision);
            topic.setName("Topic");
            revision.getTopics().add(topic);
            LessonRevision lesson = new LessonRevision();
            lesson.setTopicRevision(topic);
            lesson.setTeacher(teacher);
            lesson.setTitle("Lesson");
            lesson.setSkillType(SkillType.LISTENING);
            lesson.setAudioUrl("https://cdn/old.mp3");
            lesson.setAudioPublicId("old");
            topic.getLessons().add(lesson);
            QuestionRevision question = new QuestionRevision();
            question.setLessonRevision(lesson);
            question.setTeacher(teacher);
            question.setType(QuestionType.SHORT_ANSWER);
            question.setQuestionText("Question");
            question.setCorrectAnswer("Answer");
            lesson.getQuestions().add(question);
            revisions.save(revision);
            return course.getId();
        });
        byte[] audio = {'I', 'D', '3', 1, 2, 3};
        when(storage.downloadLessonAudio(any(), any())).thenReturn(new StorageDownloadResult("audio/mpeg", audio));
        byte[] archive = facade.exportCourse(teacher, courseId);
        Long lessonId = new TransactionTemplate(transactions).execute(status -> {
            return revisions.findTopByCourseIdOrderByRevisionNumberDesc(courseId).orElseThrow()
                    .getTopics().getFirst().getLessons().getFirst().getId();
        });
        return new Fixture(teacher, courseId, lessonId, archive);
    }

    private record Fixture(User teacher, Long courseId, Long lessonId, byte[] archive) {
        MockMultipartFile file() {
            return new MockMultipartFile("file", "course.zip", "application/zip", archive);
        }
    }
}
