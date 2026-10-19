package com.learne.backend.teacher.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.dto.StorageDownloadResult;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;
import com.learne.backend.teacher.content.entity.QuestionOptionRevision;
import com.learne.backend.teacher.content.entity.QuestionRevision;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CoursePriceStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.importexport.model.CourseImportModel;
import com.learne.backend.teacher.content.importexport.model.LessonImportModel;
import com.learne.backend.teacher.content.importexport.model.OptionImportModel;
import com.learne.backend.teacher.content.importexport.model.QuestionImportModel;
import com.learne.backend.teacher.content.importexport.model.TopicImportModel;
import com.learne.backend.teacher.content.importexport.security.AudioFileValidator;
import com.learne.backend.teacher.content.importexport.security.ClamAvClient;
import com.learne.backend.teacher.content.importexport.service.CourseImportExportService;
import com.learne.backend.teacher.content.importexport.service.CourseImportPreparationService;
import com.learne.backend.teacher.content.importexport.service.CourseImportPersistenceService;
import com.learne.backend.teacher.content.importexport.service.PreparedCourseImport;
import com.learne.backend.teacher.content.service.CourseWriteLockService;
import com.learne.backend.teacher.content.audio.service.LessonAudioCleanupService;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.importexport.template.CourseExcelTemplateExporter;
import com.learne.backend.teacher.content.importexport.template.CourseExcelTemplateParser;
import com.learne.backend.teacher.content.importexport.template.CourseExportPackageBuilder;
import com.learne.backend.teacher.content.importexport.archive.CourseImportArchiveReader;
import com.learne.backend.teacher.content.importexport.archive.CourseImportPackage;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.aop.framework.ProxyFactory;

@ExtendWith(MockitoExtension.class)
@SpringBootTest
@ActiveProfiles("test")
class CourseImportExportServiceTests {
    @Autowired PlatformTransactionManager transactions;
    private final LessonAudioCleanupService cleanup = mock(LessonAudioCleanupService.class);

    @Test
    void preparationUploadsEachReferencedPathOnceAndCopiesPreparedCollections() {
        CourseImportArchiveReader reader = mock(CourseImportArchiveReader.class);
        CourseExcelTemplateParser parser = mock(CourseExcelTemplateParser.class);
        CourseImportPersistenceService writer = mock(CourseImportPersistenceService.class);
        StorageService storage = mock(StorageService.class);
        ClamAvClient scanner = mock(ClamAvClient.class);
        CourseImportModel original = courseImportModelWithAudio();
        TopicImportModel topic = original.topics().getFirst();
        LessonImportModel lesson = topic.lessons().getFirst();
        CourseImportModel model = new CourseImportModel(null, original.name(), original.description(), original.type(),
                original.level(), 1, List.of(new TopicImportModel(null, 1, topic.title(), topic.description(),
                        List.of(lesson, lesson))));
        byte[] audio = {'I', 'D', '3', 1};
        when(reader.read(any(), any())).thenReturn(new CourseImportPackage(new byte[] {1}, Map.of("audio/lesson.mp3",
                new CourseImportPackage.ImportFile("audio/lesson.mp3", "audio/mpeg", audio))));
        when(parser.parse(any())).thenReturn(model);
        StorageUploadResult upload = new StorageUploadResult("https://cdn/new.mp3", "new");
        when(storage.uploadLessonAudio(any())).thenReturn(upload);
        CourseImportPreparationService preparation = new CourseImportPreparationService(reader, parser,
                new AudioFileValidator(), scanner, storage, writer, cleanup);

        PreparedCourseImport prepared = preparation.prepare(new User(), multipartFile());

        assertThat(prepared.uploadedByAudioPath()).containsOnly(Map.entry("audio/lesson.mp3", upload));
        assertThat(prepared.uploadedAssets()).containsExactly(upload);
        verify(storage).uploadLessonAudio(any());
        verify(scanner).assertClean(eq("audio/lesson.mp3"), any());
        Map<String, StorageUploadResult> map = new java.util.HashMap<>(prepared.uploadedByAudioPath());
        List<StorageUploadResult> list = new java.util.ArrayList<>(prepared.uploadedAssets());
        PreparedCourseImport copied = new PreparedCourseImport(model, 1L, map, list);
        map.clear();
        list.clear();
        assertThat(copied.uploadedByAudioPath()).hasSize(1);
        assertThat(copied.uploadedAssets()).containsExactly(upload);
        assertThatThrownBy(() -> copied.uploadedAssets().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> copied.uploadedByAudioPath().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preparationOffersKnownUploadPairsToSafeCleanupAndPreservesOriginalFailure() {
        CourseImportArchiveReader reader = mock(CourseImportArchiveReader.class);
        CourseExcelTemplateParser parser = mock(CourseExcelTemplateParser.class);
        CourseImportPersistenceService writer = mock(CourseImportPersistenceService.class);
        StorageService storage = mock(StorageService.class);
        CourseImportModel original = courseImportModelWithAudio();
        TopicImportModel topic = original.topics().getFirst();
        LessonImportModel lesson = topic.lessons().getFirst();
        LessonImportModel second = new LessonImportModel(null, 2, "Second", null, null, "LISTENING", 0, false,
                "audio/second.mp3", lesson.questions());
        CourseImportModel model = new CourseImportModel(42L, original.name(), original.description(), original.type(),
                original.level(), 1, List.of(new TopicImportModel(null, 1, topic.title(), topic.description(),
                        List.of(lesson, second))));
        byte[] audio = {'I', 'D', '3', 1};
        when(reader.read(any(), any())).thenReturn(new CourseImportPackage(new byte[] {1}, Map.of(
                "audio/lesson.mp3", new CourseImportPackage.ImportFile("audio/lesson.mp3", "audio/mpeg", audio),
                "audio/second.mp3", new CourseImportPackage.ImportFile("audio/second.mp3", "audio/mpeg", audio))));
        when(parser.parse(any())).thenReturn(model);
        RuntimeException failure = new IllegalStateException("provider failure");
        when(storage.uploadLessonAudio(any())).thenReturn(new StorageUploadResult("https://cdn/new.mp3", "new"))
                .thenThrow(failure);
        CourseImportPreparationService preparation = new CourseImportPreparationService(reader, parser,
                new AudioFileValidator(), mock(ClamAvClient.class), storage, writer, cleanup);
        doThrow(new IllegalStateException("cleanup failed")).when(cleanup).tryCleanup(any(), any());

        assertThatThrownBy(() -> preparation.prepare(new User(), multipartFile())).isSameAs(failure);

        verify(storage, never()).delete(any(), any());
        verify(cleanup).tryCleanup(42L, List.of(new LessonAudioState("https://cdn/new.mp3", "new")));
        verify(writer, never()).persist(any(), any(), any());
    }

    @Test
    void facadeRetainsCommittedUploadWhenTransactionProxyThrowsAfterCommit() {
        CourseImportArchiveReader reader = mock(CourseImportArchiveReader.class);
        CourseExcelTemplateParser parser = mock(CourseExcelTemplateParser.class);
        CourseRepository courses = mock(CourseRepository.class);
        CourseRevisionRepository revisions = mock(CourseRevisionRepository.class);
        StorageService storage = mock(StorageService.class);
        when(reader.read(any(), any())).thenReturn(new CourseImportPackage(new byte[] {1}, Map.of("audio/lesson.mp3",
                new CourseImportPackage.ImportFile("audio/lesson.mp3", "audio/mpeg", new byte[] {'I', 'D', '3', 1}))));
        when(parser.parse(any())).thenReturn(courseImportModelWithAudio());
        when(storage.uploadLessonAudio(any())).thenReturn(new StorageUploadResult("https://cdn/new.mp3", "new"));
        when(courses.save(any())).thenAnswer(call -> call.getArgument(0));
        when(revisions.save(any())).thenAnswer(call -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { throw new IllegalStateException("post commit failure"); }
            });
            return call.getArgument(0);
        });
        CourseImportExportService facade = service(reader, parser, mock(CourseExcelTemplateExporter.class),
                new CourseExportPackageBuilder(), new AudioFileValidator(), mock(ClamAvClient.class), storage, courses, revisions);

        assertThatThrownBy(() -> facade.importCourse(new User(), multipartFile())).hasMessage("post commit failure");

        verify(storage, never()).delete(any(), any());
        verifyNoInteractions(cleanup);
    }

    @Test
    void facadeRetainsUploadWhenPersistenceThrowsWithoutTransactionOutcomeEvidence() {
        CourseImportPreparationService preparation = mock(CourseImportPreparationService.class);
        CourseImportPersistenceService writer = mock(CourseImportPersistenceService.class);
        StorageService storage = mock(StorageService.class);
        PreparedCourseImport prepared = new PreparedCourseImport(courseImportModelWithAudio(), null, Map.of(),
                List.of(new StorageUploadResult("https://cdn/new.mp3", "new")));
        when(preparation.prepare(any(), any())).thenReturn(prepared);
        RuntimeException failure = new IllegalStateException("completion status unknown");
        when(writer.persist(any(), any(), any())).thenThrow(failure);
        CourseImportExportService facade = new CourseImportExportService(preparation, writer,
                mock(CourseExcelTemplateExporter.class), new CourseExportPackageBuilder(), new AudioFileValidator(),
                storage, mock(CourseRepository.class), mock(CourseRevisionRepository.class), cleanup);

        assertThatThrownBy(() -> facade.importCourse(new User(), multipartFile())).isSameAs(failure);

        verifyNoInteractions(cleanup, storage);
    }
    @Test
    void exportedZipImportsThroughRealArchiveAndParserPreservingGraphIdsSourceLinksAndAudio() throws Exception {
        RoundTripFixture fixture = roundTripFixture(true, 2);
        byte[] exported = fixture.export();

        fixture.importPackage(exported);

        CourseRevision imported = fixture.savedRevision();
        assertThat(imported.getTopics()).hasSize(1);
        assertThat(zipEntryBytes(exported, "audio/lesson-200-hello.mp3"))
                .isEqualTo(fixture.downloadedAudioBytes);
        TopicRevision topic = imported.getTopics().getFirst();
        assertThat(topic.getSourceTopic()).isSameAs(fixture.sourceTopic);
        LessonRevision lesson = topic.getLessons().getFirst();
        assertThat(lesson.getSourceLesson()).isSameAs(fixture.sourceLesson);
        assertThat(lesson.getAudioUrl()).isEqualTo("https://cdn/imported-audio.mp3");
        assertThat(lesson.getAudioPublicId()).isEqualTo("imported-audio-id");
        assertThat(lesson.getQuestions()).extracting(QuestionRevision::getSourceQuestion)
                .containsExactly(fixture.sourceQuestions.get(0), fixture.sourceQuestions.get(1));
        assertThat(lesson.getQuestions().getFirst().getOptions()).extracting(QuestionOptionRevision::getSourceOption)
                .containsExactly(fixture.sourceOptions.get(0), fixture.sourceOptions.get(1));
        verify(fixture.storageService).uploadLessonAudio(any());
    }

    @Test
    void deletingExportedContentRowRemovesQuestionOnRealReimport() throws Exception {
        RoundTripFixture fixture = roundTripFixture(false, 2);
        byte[] exported = fixture.export();
        byte[] editedWorkbook = removeContentRow(workbookFromZip(exported), 2);

        fixture.importPackage(packageWithWorkbook(editedWorkbook));

        assertThat(fixture.savedRevision().getTopics().getFirst().getLessons().getFirst().getQuestions())
                .extracting(QuestionRevision::getSourceQuestion)
                .containsExactly(fixture.sourceQuestions.get(0));
    }

    @Test
    void addingContentRowWithBlankQuestionIdCreatesNewQuestionOnRealReimport() throws Exception {
        RoundTripFixture fixture = roundTripFixture(false, 1);
        byte[] exported = fixture.export();
        byte[] editedWorkbook = appendBlankQuestionRow(workbookFromZip(exported));

        fixture.importPackage(packageWithWorkbook(editedWorkbook));

        List<QuestionRevision> questions = fixture.savedRevision().getTopics().getFirst().getLessons().getFirst().getQuestions();
        assertThat(questions).hasSize(2);
        assertThat(questions.get(0).getSourceQuestion()).isSameAs(fixture.sourceQuestions.getFirst());
        assertThat(questions.get(1).getSourceQuestion()).isNull();
        assertThat(questions.get(1).getQuestionText()).isEqualTo("A newly added question");
    }

    @Test
    void exportCourseDownloadsValidatesAndPackagesLessonAudioAtDeterministicPath() throws Exception {
        ExportFixture fixture = exportFixture();
        CourseExportPackageBuilder packageBuilder = new CourseExportPackageBuilder();
        when(fixture.storageService.downloadLessonAudio("audio-public-id", "https://cdn/audio.mp3"))
                .thenReturn(new StorageDownloadResult("audio/mpeg", new byte[] {'I', 'D', '3', 1, 2, 3}));
        CourseImportExportService service = fixture.service(packageBuilder, new AudioFileValidator(),
                new CourseExcelTemplateExporter());

        byte[] result = service.exportCourse(fixture.teacher, 42L);

        assertThat(zipEntryNames(result)).containsExactly("course-42.xlsm", "audio/lesson-30-listening-practice.mp3");
        assertThat(workbookAudioPath(result)).isEqualTo("audio/lesson-30-listening-practice.mp3");
        verify(fixture.storageService).downloadLessonAudio("audio-public-id", "https://cdn/audio.mp3");
    }

    @Test
    void exportCourseFailsWhenReferencedAudioIsMissingAndDoesNotBuildPackage() {
        ExportFixture fixture = exportFixture();
        CourseExportPackageBuilder packageBuilder = mock(CourseExportPackageBuilder.class);
        when(fixture.storageService.downloadLessonAudio("audio-public-id", "https://cdn/audio.mp3"))
                .thenReturn(null);
        CourseImportExportService service = fixture.service(packageBuilder, new AudioFileValidator());

        assertThatThrownBy(() -> service.exportCourse(fixture.teacher, 42L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("audio");
        verifyNoInteractions(packageBuilder);
    }

    @Test
    void exportCourseFailsWhenReferencedAudioDownloadFailsAndDoesNotBuildPackage() {
        ExportFixture fixture = exportFixture();
        CourseExportPackageBuilder packageBuilder = mock(CourseExportPackageBuilder.class);
        when(fixture.storageService.downloadLessonAudio("audio-public-id", "https://cdn/audio.mp3"))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "Không thể tải audio bài học từ Cloudinary."));
        CourseImportExportService service = fixture.service(packageBuilder, new AudioFileValidator());

        assertThatThrownBy(() -> service.exportCourse(fixture.teacher, 42L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("audio")
                .hasMessageContaining("30")
                .hasMessageContaining("Listening Practice");
        verifyNoInteractions(packageBuilder);
    }

    @Test
    void exportCourseConvertsProviderStatusToBadGatewayWithLessonContext() {
        ExportFixture fixture = exportFixture();
        CourseExportPackageBuilder packageBuilder = mock(CourseExportPackageBuilder.class);
        when(fixture.storageService.downloadLessonAudio("audio-public-id", "https://cdn/audio.mp3"))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "provider failed"));
        CourseImportExportService service = fixture.service(packageBuilder, new AudioFileValidator());

        assertThatThrownBy(() -> service.exportCourse(fixture.teacher, 42L))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_GATEWAY);
                    assertThat(exception.getMessage()).contains("30", "Listening Practice");
                });
        verifyNoInteractions(packageBuilder);
    }

    @Test
    void exportCourseFailsWhenDownloadedAudioIsInvalidAndDoesNotBuildPackage() {
        ExportFixture fixture = exportFixture();
        CourseExportPackageBuilder packageBuilder = mock(CourseExportPackageBuilder.class);
        when(fixture.storageService.downloadLessonAudio("audio-public-id", "https://cdn/audio.mp3"))
                .thenReturn(new StorageDownloadResult("audio/mpeg", "not audio".getBytes()));
        CourseImportExportService service = fixture.service(packageBuilder, new AudioFileValidator());

        assertThatThrownBy(() -> service.exportCourse(fixture.teacher, 42L))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR))
                .hasMessageContaining("30")
                .hasMessageContaining("Listening Practice");
        verifyNoInteractions(packageBuilder);
    }

    @Test
    void exportCourseFailsWhenDownloadedAudioIsOversizedAndDoesNotBuildPackage() {
        ExportFixture fixture = exportFixture();
        CourseExportPackageBuilder packageBuilder = mock(CourseExportPackageBuilder.class);
        when(fixture.storageService.downloadLessonAudio("audio-public-id", "https://cdn/audio.mp3"))
                .thenReturn(new StorageDownloadResult("audio/mpeg",
                        new byte[(int) AudioFileValidator.MAX_AUDIO_BYTES + 1]));
        CourseImportExportService service = fixture.service(packageBuilder, new AudioFileValidator());

        assertThatThrownBy(() -> service.exportCourse(fixture.teacher, 42L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("dung lượng");
        verifyNoInteractions(packageBuilder);
    }

    @Test
    void exportCourseRejectsAudioLessonWithoutContentRowBeforeDownloadingAudio() {
        ExportFixture fixture = exportFixture();
        fixture.revision.getTopics().getFirst().getLessons().getFirst().getQuestions().clear();
        CourseExportPackageBuilder packageBuilder = mock(CourseExportPackageBuilder.class);
        CourseImportExportService service = fixture.service(packageBuilder, new AudioFileValidator());

        assertThatThrownBy(() -> service.exportCourse(fixture.teacher, 42L))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST))
                .hasMessageContaining("ít nhất một câu hỏi");
        verifyNoInteractions(fixture.storageService, packageBuilder);
    }

    @Test
    void exportCourseRejectsEmptyTopicWithBadRequest() {
        ExportFixture fixture = exportFixture();
        fixture.revision.getTopics().getFirst().getLessons().clear();
        CourseExportPackageBuilder packageBuilder = mock(CourseExportPackageBuilder.class);
        CourseImportExportService service = fixture.service(packageBuilder, new AudioFileValidator());

        assertThatThrownBy(() -> service.exportCourse(fixture.teacher, 42L))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST))
                .hasMessageContaining("ít nhất một bài học");
        verifyNoInteractions(fixture.storageService, packageBuilder);
    }

    @Test
    void exportCourseRejectsRevisionWithNoTopicsBeforeExporterStorageOrPackage() {
        ExportFixture fixture = exportFixture();
        fixture.revision.getTopics().clear();
        CourseExportPackageBuilder packageBuilder = mock(CourseExportPackageBuilder.class);
        CourseImportExportService service = fixture.service(packageBuilder, new AudioFileValidator());

        assertThatThrownBy(() -> service.exportCourse(fixture.teacher, 42L))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Listening Course")
                .hasMessageContaining("ít nhất một chủ đề");
        verifyNoInteractions(fixture.storageService, fixture.templateExporter, packageBuilder);
    }

    @Test
    void exportCourseRejectsQuestionWithMoreThanFourOptionsBeforePackageCreation() {
        ExportFixture fixture = exportFixture();
        QuestionRevision question = fixture.revision.getTopics().getFirst().getLessons().getFirst().getQuestions().getFirst();
        for (int index = 0; index < 5; index++) {
            QuestionOptionRevision option = new QuestionOptionRevision();
            option.setOptionText("Option " + index);
            option.setOrderIndex(index + 1);
            question.getOptions().add(option);
        }
        CourseExportPackageBuilder packageBuilder = mock(CourseExportPackageBuilder.class);
        CourseImportExportService service = fixture.service(packageBuilder, new AudioFileValidator());

        assertThatThrownBy(() -> service.exportCourse(fixture.teacher, 42L))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).contains("4", "lựa chọn");
                });
        verifyNoInteractions(fixture.storageService, fixture.templateExporter, packageBuilder);
    }

    @Test
    void exportCourseRejectsEmptyLessonWithBadRequest() {
        ExportFixture fixture = exportFixture();
        LessonRevision lesson = fixture.revision.getTopics().getFirst().getLessons().getFirst();
        lesson.getQuestions().clear();
        lesson.setAudioUrl(null);
        lesson.setAudioPublicId(null);
        CourseExportPackageBuilder packageBuilder = mock(CourseExportPackageBuilder.class);
        CourseImportExportService service = fixture.service(packageBuilder, new AudioFileValidator());

        assertThatThrownBy(() -> service.exportCourse(fixture.teacher, 42L))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST))
                .hasMessageContaining("ít nhất một câu hỏi");
        verifyNoInteractions(fixture.storageService, packageBuilder);
    }

    @Test
    void importCourseRejectsMissingReferencedAudio() {
        CourseImportArchiveReader zipReader = mock(CourseImportArchiveReader.class);
        CourseExcelTemplateParser templateParser = mock(CourseExcelTemplateParser.class);
        CourseExcelTemplateExporter templateExporter = mock(CourseExcelTemplateExporter.class);
        AudioFileValidator audioFileValidator = mock(AudioFileValidator.class);
        ClamAvClient clamAvClient = mock(ClamAvClient.class);
        StorageService storageService = mock(StorageService.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseRevisionRepository courseRevisionRepository = mock(CourseRevisionRepository.class);
        CourseImportExportService service = service(zipReader, templateParser, templateExporter,
                new CourseExportPackageBuilder(),
                audioFileValidator, clamAvClient, storageService, courseRepository, courseRevisionRepository);
        CourseImportPackage importPackage = new CourseImportPackage(new byte[] {1}, Map.of());
        when(zipReader.read(any(), eq("course.rar"))).thenReturn(importPackage);
        when(templateParser.parse(importPackage.workbook())).thenReturn(courseImportModelWithAudio());

        assertThatThrownBy(() -> service.importCourse(new User(), new MockMultipartFile(
                "file", "course.rar", "application/vnd.rar", new byte[] {1})))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("audio/lesson.mp3");
    }

    @Test
    void importCourseRejectsUnusedAudioFileInZip() {
        CourseImportArchiveReader zipReader = mock(CourseImportArchiveReader.class);
        CourseExcelTemplateParser templateParser = mock(CourseExcelTemplateParser.class);
        CourseExcelTemplateExporter templateExporter = mock(CourseExcelTemplateExporter.class);
        AudioFileValidator audioFileValidator = mock(AudioFileValidator.class);
        ClamAvClient clamAvClient = mock(ClamAvClient.class);
        StorageService storageService = mock(StorageService.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseRevisionRepository courseRevisionRepository = mock(CourseRevisionRepository.class);
        CourseImportExportService service = service(zipReader, templateParser, templateExporter,
                new CourseExportPackageBuilder(),
                audioFileValidator, clamAvClient, storageService, courseRepository, courseRevisionRepository);
        CourseImportPackage importPackage = new CourseImportPackage(new byte[] {1},
                Map.of("audio/unused.mp3", new CourseImportPackage.ImportFile(
                        "audio/unused.mp3", "audio/mpeg", new byte[] {1})));
        when(zipReader.read(any(), any())).thenReturn(importPackage);
        when(templateParser.parse(importPackage.workbook())).thenReturn(courseImportModel(null, null));

        assertThatThrownBy(() -> service.importCourse(new User(), new MockMultipartFile(
                "file", "course.zip", "application/zip", new byte[] {1})))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("không được tham chiếu");
    }

    @Test
    void confirmedRollbackOffersKnownUploadPairsToSafeCleanupAndPreservesOriginalFailure() {
        CourseImportArchiveReader zipReader = mock(CourseImportArchiveReader.class);
        CourseExcelTemplateParser templateParser = mock(CourseExcelTemplateParser.class);
        CourseExcelTemplateExporter templateExporter = mock(CourseExcelTemplateExporter.class);
        AudioFileValidator audioFileValidator = mock(AudioFileValidator.class);
        ClamAvClient clamAvClient = mock(ClamAvClient.class);
        StorageService storageService = mock(StorageService.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseRevisionRepository courseRevisionRepository = mock(CourseRevisionRepository.class);
        CourseImportExportService service = service(zipReader, templateParser, templateExporter,
                new CourseExportPackageBuilder(),
                audioFileValidator, clamAvClient, storageService, courseRepository, courseRevisionRepository);
        RuntimeException persistenceFailure = new RuntimeException("revision save failed");

        CourseImportPackage importPackage = new CourseImportPackage(
                new byte[] {1},
                Map.of("audio/lesson.mp3", new CourseImportPackage.ImportFile(
                        "audio/lesson.mp3", "audio/mpeg", new byte[] {1, 2, 3})));
        when(zipReader.read(any(), any())).thenReturn(importPackage);
        when(templateParser.parse(importPackage.workbook())).thenReturn(courseImportModelWithAudio());
        when(storageService.uploadLessonAudio(any())).thenReturn(new StorageUploadResult("https://cdn/audio.mp3", "audio-public-id"));
        when(courseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0, Course.class));
        doThrow(persistenceFailure).when(courseRevisionRepository).save(any());
        doThrow(new IllegalStateException("cleanup failure")).when(cleanup).tryCleanup(any(), any());

        assertThatThrownBy(() -> service.importCourse(new User(), new MockMultipartFile(
                "file", "course.zip", "application/zip", new byte[] {1})))
                .isSameAs(persistenceFailure);

        verify(storageService, never()).delete(any(), any());
        verify(cleanup).tryCleanup(null, List.of(new LessonAudioState("https://cdn/audio.mp3", "audio-public-id")));
    }

    @Test
    void importNewCoursePersistsVisibleDraftContentOnCourse() {
        CourseImportArchiveReader zipReader = mock(CourseImportArchiveReader.class);
        CourseExcelTemplateParser templateParser = mock(CourseExcelTemplateParser.class);
        CourseExcelTemplateExporter templateExporter = mock(CourseExcelTemplateExporter.class);
        AudioFileValidator audioFileValidator = mock(AudioFileValidator.class);
        ClamAvClient clamAvClient = mock(ClamAvClient.class);
        StorageService storageService = mock(StorageService.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseRevisionRepository courseRevisionRepository = mock(CourseRevisionRepository.class);
        CourseImportExportService service = service(zipReader, templateParser, templateExporter,
                new CourseExportPackageBuilder(),
                audioFileValidator, clamAvClient, storageService, courseRepository, courseRevisionRepository);
        ArgumentCaptor<Course> courseCaptor = ArgumentCaptor.forClass(Course.class);
        CourseImportPackage importPackage = new CourseImportPackage(new byte[] {1}, Map.of());

        when(zipReader.read(any(), any())).thenReturn(importPackage);
        when(templateParser.parse(importPackage.workbook())).thenReturn(courseImportModel(null, null));
        when(courseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0, Course.class));
        when(courseRevisionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.importCourse(new User(), new MockMultipartFile("file", "course.zip", "application/zip", new byte[] {1}));

        verify(courseRepository).save(courseCaptor.capture());
        Course savedCourse = courseCaptor.getValue();
        assertThat(savedCourse.getTopics()).hasSize(1);
        assertThat(savedCourse.getTopics().getFirst().getName()).isEqualTo("Greetings");
        assertThat(savedCourse.getTopics().getFirst().getLessons()).hasSize(1);
        assertThat(savedCourse.getTopics().getFirst().getLessons().getFirst().getTitle()).isEqualTo("Hello");
        assertThat(savedCourse.getTopics().getFirst().getLessons().getFirst().getQuestions()).hasSize(1);
        assertThat(savedCourse.getTopics().getFirst().getLessons().getFirst().getQuestions().getFirst().getQuestionText())
                .isEqualTo("Say hello");
    }

    @Test
    void committedImportNeverDeletesUploadedAudioFromCompletionCallback() {
        CourseImportArchiveReader zipReader = mock(CourseImportArchiveReader.class);
        CourseExcelTemplateParser templateParser = mock(CourseExcelTemplateParser.class);
        CourseExcelTemplateExporter templateExporter = mock(CourseExcelTemplateExporter.class);
        AudioFileValidator audioFileValidator = mock(AudioFileValidator.class);
        ClamAvClient clamAvClient = mock(ClamAvClient.class);
        StorageService storageService = mock(StorageService.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseRevisionRepository courseRevisionRepository = mock(CourseRevisionRepository.class);
        CourseImportExportService service = service(zipReader, templateParser, templateExporter,
                new CourseExportPackageBuilder(),
                audioFileValidator, clamAvClient, storageService, courseRepository, courseRevisionRepository);

        CourseImportPackage importPackage = new CourseImportPackage(
                new byte[] {1},
                Map.of("audio/lesson.mp3", new CourseImportPackage.ImportFile(
                        "audio/lesson.mp3", "audio/mpeg", new byte[] {1, 2, 3})));
        when(zipReader.read(any(), any())).thenReturn(importPackage);
        when(templateParser.parse(importPackage.workbook())).thenReturn(courseImportModelWithAudio());
        when(storageService.uploadLessonAudio(any())).thenReturn(new StorageUploadResult("https://cdn/audio.mp3", "audio-public-id"));
        when(courseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0, Course.class));
        when(courseRevisionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.importCourse(new User(), new MockMultipartFile("file", "course.zip", "application/zip", new byte[] {1}));

        verify(storageService, never()).delete(any(), any());
    }

    @Test
    void reimportApprovedCourseCopiesPricingMetadataToNewDraftRevision() {
        CourseImportArchiveReader zipReader = mock(CourseImportArchiveReader.class);
        CourseExcelTemplateParser templateParser = mock(CourseExcelTemplateParser.class);
        CourseExcelTemplateExporter templateExporter = mock(CourseExcelTemplateExporter.class);
        AudioFileValidator audioFileValidator = mock(AudioFileValidator.class);
        ClamAvClient clamAvClient = mock(ClamAvClient.class);
        StorageService storageService = mock(StorageService.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseRevisionRepository courseRevisionRepository = mock(CourseRevisionRepository.class);
        CourseImportExportService service = service(zipReader, templateParser, templateExporter,
                new CourseExportPackageBuilder(),
                audioFileValidator, clamAvClient, storageService, courseRepository, courseRevisionRepository);
        User teacher = new User();
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", 42L);
        course.setTeacher(teacher);
        course.setStatus(ContentStatus.APPROVED);
        course.setPricingType(CoursePricingType.PAID);
        course.setProposedPriceAmountVnd(300000L);
        course.setPriceStatus(CoursePriceStatus.PENDING_REVIEW);
        CourseRevision sourceRevision = new CourseRevision();
        sourceRevision.setCourse(course);
        sourceRevision.setTeacher(teacher);
        sourceRevision.setRevisionNumber(1);
        sourceRevision.setStatus(CourseRevisionStatus.APPROVED);
        sourceRevision.setPricingType(CoursePricingType.PAID);
        sourceRevision.setProposedPriceAmountVnd(300000L);
        CourseImportPackage importPackage = new CourseImportPackage(new byte[] {1}, Map.of());
        CourseImportModel model = courseImportModel(42L, null);
        ArgumentCaptor<CourseRevision> revisionCaptor = ArgumentCaptor.forClass(CourseRevision.class);

        when(zipReader.read(any(), any())).thenReturn(importPackage);
        when(templateParser.parse(importPackage.workbook())).thenReturn(model);
        when(courseRepository.findByIdAndTeacher(42L, teacher)).thenReturn(Optional.of(course));
        when(courseRevisionRepository.findTopByCourseOrderByRevisionNumberDesc(course)).thenReturn(Optional.of(sourceRevision));
        when(courseRevisionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.importCourse(teacher, new MockMultipartFile("file", "course.zip", "application/zip", new byte[] {1}));

        verify(courseRevisionRepository).save(revisionCaptor.capture());
        CourseRevision savedRevision = revisionCaptor.getValue();
        assertThat(savedRevision.getPricingType()).isEqualTo(CoursePricingType.PAID);
        assertThat(savedRevision.getProposedPriceAmountVnd()).isEqualTo(300000L);
    }

    @Test
    void reimportPendingCourseSupersedesSourceRevision() {
        CourseImportArchiveReader zipReader = mock(CourseImportArchiveReader.class);
        CourseExcelTemplateParser templateParser = mock(CourseExcelTemplateParser.class);
        CourseExcelTemplateExporter templateExporter = mock(CourseExcelTemplateExporter.class);
        AudioFileValidator audioFileValidator = mock(AudioFileValidator.class);
        ClamAvClient clamAvClient = mock(ClamAvClient.class);
        StorageService storageService = mock(StorageService.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseRevisionRepository courseRevisionRepository = mock(CourseRevisionRepository.class);
        CourseImportExportService service = service(zipReader, templateParser, templateExporter,
                new CourseExportPackageBuilder(),
                audioFileValidator, clamAvClient, storageService, courseRepository, courseRevisionRepository);
        User teacher = new User();
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", 42L);
        course.setTeacher(teacher);
        course.setStatus(ContentStatus.APPROVED);
        CourseRevision sourceRevision = new CourseRevision();
        sourceRevision.setCourse(course);
        sourceRevision.setTeacher(teacher);
        sourceRevision.setRevisionNumber(2);
        sourceRevision.setStatus(CourseRevisionStatus.PENDING_REVIEW);
        CourseImportPackage importPackage = new CourseImportPackage(new byte[] {1}, Map.of());
        CourseImportModel model = courseImportModel(42L, null);
        ArgumentCaptor<CourseRevision> revisionCaptor = ArgumentCaptor.forClass(CourseRevision.class);

        when(zipReader.read(any(), any())).thenReturn(importPackage);
        when(templateParser.parse(importPackage.workbook())).thenReturn(model);
        when(courseRepository.findByIdAndTeacher(42L, teacher)).thenReturn(Optional.of(course));
        when(courseRevisionRepository.findTopByCourseOrderByRevisionNumberDesc(course)).thenReturn(Optional.of(sourceRevision));
        when(courseRevisionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.importCourse(teacher, new MockMultipartFile("file", "course.zip", "application/zip", new byte[] {1}));

        verify(courseRevisionRepository).save(revisionCaptor.capture());
        CourseRevision savedRevision = revisionCaptor.getValue();
        assertThat(savedRevision).isNotSameAs(sourceRevision);
        assertThat(savedRevision.getStatus()).isEqualTo(CourseRevisionStatus.DRAFT);
        assertThat(sourceRevision.getStatus()).isEqualTo(CourseRevisionStatus.SUPERSEDED);
        assertThat(course.getStatus()).isEqualTo(ContentStatus.DRAFT);
    }

    @Test
    void reimportRejectsChildIdWithoutParentId() {
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", 42L);
        User teacher = new User();
        CourseRevision sourceRevision = sourceRevision(course, teacher);
        LessonRevision lesson = lessonRevision(sourceRevision.getTopics().getFirst(), 200L);
        sourceRevision.getTopics().getFirst().getLessons().add(lesson);

        CourseImportExportService service = serviceForReimport(course, teacher, sourceRevision,
                new CourseImportModel(42L, "Imported English A1", "Description", "COMPREHENSIVE", "A1", 1,
                        List.of(new TopicImportModel(null, 1, "Greetings", "Basic greetings",
                                List.of(new LessonImportModel(200L, 1, "Hello", "Lesson", "Content", "READING",
                                        10, false, null, List.of(shortAnswerQuestion(null))))))));

        assertThatThrownBy(() -> service.importCourse(teacher, multipartFile()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ID bài học yêu cầu ID chủ đề");
    }

    @Test
    void reimportRejectsDuplicateQuestionIds() {
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", 42L);
        User teacher = new User();
        CourseRevision sourceRevision = sourceRevision(course, teacher);
        TopicRevision topic = sourceRevision.getTopics().getFirst();
        LessonRevision lesson = lessonRevision(topic, 200L);
        topic.getLessons().add(lesson);
        QuestionRevision question = questionRevision(lesson, 300L);
        lesson.getQuestions().add(question);
        QuestionImportModel duplicateQuestion = shortAnswerQuestion(300L);
        CourseImportModel model = reimportModel(List.of(new TopicImportModel(100L, 1, "Greetings", "Basic greetings",
                List.of(new LessonImportModel(200L, 1, "Hello", "Lesson", "Content", "READING", 10, false, null,
                        List.of(duplicateQuestion, duplicateQuestion))))));
        CourseImportExportService service = serviceForReimport(course, teacher, sourceRevision, model);

        assertThatThrownBy(() -> service.importCourse(teacher, multipartFile()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ID câu hỏi bị trùng");
    }

    @Test
    void reimportRejectsDuplicateOptionIds() {
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", 42L);
        User teacher = new User();
        CourseRevision sourceRevision = sourceRevision(course, teacher);
        TopicRevision topic = sourceRevision.getTopics().getFirst();
        LessonRevision lesson = lessonRevision(topic, 200L);
        topic.getLessons().add(lesson);
        QuestionRevision question = questionRevision(lesson, 300L);
        lesson.getQuestions().add(question);
        question.getOptions().add(optionRevision(question, 400L));
        CourseImportModel model = reimportModel(List.of(new TopicImportModel(100L, 1, "Greetings", "Basic greetings",
                List.of(new LessonImportModel(200L, 1, "Hello", "Lesson", "Content", "READING", 10, false, null,
                        List.of(new QuestionImportModel(300L, 1, "MULTIPLE_CHOICE", "Choose", null, null,
                                "greetings", "easy", 5, List.of(new OptionImportModel(400L, "Yes", true, 1),
                                        new OptionImportModel(400L, "No", false, 2)))))))));
        CourseImportExportService service = serviceForReimport(course, teacher, sourceRevision, model);

        assertThatThrownBy(() -> service.importCourse(teacher, multipartFile()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ID đáp án bị trùng");
    }

    @Test
    void reimportRejectsStaleQuestionId() {
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", 42L);
        User teacher = new User();
        CourseRevision sourceRevision = sourceRevision(course, teacher);
        TopicRevision topic = sourceRevision.getTopics().getFirst();
        LessonRevision lesson = lessonRevision(topic, 200L);
        topic.getLessons().add(lesson);
        CourseImportModel model = reimportModel(List.of(new TopicImportModel(100L, 1, "Greetings", "Basic greetings",
                List.of(new LessonImportModel(200L, 1, "Hello", "Lesson", "Content", "READING", 10, false, null,
                        List.of(shortAnswerQuestion(999L)))))));
        CourseImportExportService service = serviceForReimport(course, teacher, sourceRevision, model);

        assertThatThrownBy(() -> service.importCourse(teacher, multipartFile()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ID câu hỏi trong file không thuộc");
    }

    @Test
    void reimportRejectsQuestionIdFromWrongLesson() {
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", 42L);
        User teacher = new User();
        CourseRevision sourceRevision = sourceRevision(course, teacher);
        TopicRevision topic = sourceRevision.getTopics().getFirst();
        LessonRevision firstLesson = lessonRevision(topic, 200L);
        LessonRevision secondLesson = lessonRevision(topic, 201L);
        topic.getLessons().add(firstLesson);
        topic.getLessons().add(secondLesson);
        QuestionRevision question = questionRevision(secondLesson, 300L);
        secondLesson.getQuestions().add(question);
        CourseImportModel model = reimportModel(List.of(new TopicImportModel(100L, 1, "Greetings", "Basic greetings",
                List.of(new LessonImportModel(200L, 1, "Hello", "Lesson", "Content", "READING", 10, false, null,
                        List.of(shortAnswerQuestion(300L)))))));
        CourseImportExportService service = serviceForReimport(course, teacher, sourceRevision, model);

        assertThatThrownBy(() -> service.importCourse(teacher, multipartFile()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ID câu hỏi không thuộc bài học");
    }

    @Test
    void reimportParsesRevisionIdsAndPreservesSourceLinks() throws Exception {
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", 42L);
        User teacher = new User();
        CourseRevision sourceRevision = sourceRevision(course, teacher);
        TopicRevision topicRevision = sourceRevision.getTopics().getFirst();
        Topic sourceTopic = new Topic();
        ReflectionTestUtils.setField(sourceTopic, "id", 1000L);
        topicRevision.setSourceTopic(sourceTopic);
        LessonRevision lessonRevision = lessonRevision(topicRevision, 200L);
        Lesson sourceLesson = new Lesson();
        ReflectionTestUtils.setField(sourceLesson, "id", 2000L);
        lessonRevision.setSourceLesson(sourceLesson);
        topicRevision.getLessons().add(lessonRevision);
        QuestionRevision firstQuestion = questionRevision(lessonRevision, 300L);
        Question firstSourceQuestion = new Question();
        ReflectionTestUtils.setField(firstSourceQuestion, "id", 3000L);
        firstQuestion.setSourceQuestion(firstSourceQuestion);
        QuestionRevision secondQuestion = questionRevision(lessonRevision, 301L);
        Question secondSourceQuestion = new Question();
        ReflectionTestUtils.setField(secondSourceQuestion, "id", 3001L);
        secondQuestion.setSourceQuestion(secondSourceQuestion);
        lessonRevision.getQuestions().add(firstQuestion);
        lessonRevision.getQuestions().add(secondQuestion);

        CourseImportArchiveReader archiveReader = mock(CourseImportArchiveReader.class);
        CourseImportPackage importPackage = new CourseImportPackage(revisionWorkbook(), Map.of());
        when(archiveReader.read(any(), any())).thenReturn(importPackage);
        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseRevisionRepository courseRevisionRepository = mock(CourseRevisionRepository.class);
        when(courseRepository.findByIdAndTeacher(42L, teacher)).thenReturn(Optional.of(course));
        when(courseRevisionRepository.findTopByCourseOrderByRevisionNumberDesc(course)).thenReturn(Optional.of(sourceRevision));
        when(courseRevisionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<CourseRevision> revisionCaptor = ArgumentCaptor.forClass(CourseRevision.class);
        CourseImportExportService service = service(archiveReader,
                new CourseExcelTemplateParser(), mock(CourseExcelTemplateExporter.class), new CourseExportPackageBuilder(),
                mock(AudioFileValidator.class),
                mock(ClamAvClient.class), mock(StorageService.class), courseRepository, courseRevisionRepository);

        service.importCourse(teacher, multipartFile());

        verify(courseRevisionRepository).save(revisionCaptor.capture());
        CourseRevision savedRevision = revisionCaptor.getValue();
        assertThat(savedRevision.getTopics().getFirst().getSourceTopic()).isSameAs(sourceTopic);
        assertThat(savedRevision.getTopics().getFirst().getLessons().getFirst().getSourceLesson()).isSameAs(sourceLesson);
        assertThat(savedRevision.getTopics().getFirst().getLessons().getFirst().getQuestions())
                .extracting(question -> question.getSourceQuestion().getId())
                .containsExactly(3000L, 3001L);
    }

    private ExportFixture exportFixture() {
        User teacher = new User();
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", 42L);
        course.setTeacher(teacher);
        course.setName("Listening Course");
        CourseRevision revision = new CourseRevision();
        revision.setCourse(course);
        revision.setTeacher(teacher);
        revision.setStatus(CourseRevisionStatus.DRAFT);
        TopicRevision topic = new TopicRevision();
        topic.setName("Listening");
        revision.getTopics().add(topic);
        LessonRevision lesson = new LessonRevision();
        ReflectionTestUtils.setField(lesson, "id", 30L);
        lesson.setTitle("Listening Practice");
        lesson.setAudioUrl("https://cdn/audio.mp3");
        lesson.setAudioPublicId("audio-public-id");
        topic.getLessons().add(lesson);
        QuestionRevision question = new QuestionRevision();
        question.setQuestionText("Listen");
        lesson.getQuestions().add(question);

        CourseImportArchiveReader archiveReader = mock(CourseImportArchiveReader.class);
        CourseExcelTemplateParser templateParser = mock(CourseExcelTemplateParser.class);
        CourseExcelTemplateExporter templateExporter = mock(CourseExcelTemplateExporter.class);
        StorageService storageService = mock(StorageService.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseRevisionRepository courseRevisionRepository = mock(CourseRevisionRepository.class);
        when(courseRepository.findByIdAndTeacher(42L, teacher)).thenReturn(Optional.of(course));
        when(courseRevisionRepository.findTopByCourseOrderByRevisionNumberDesc(course)).thenReturn(Optional.of(revision));
        return new ExportFixture(teacher, revision, templateExporter, storageService, courseRepository,
                courseRevisionRepository, archiveReader, templateParser);
    }

    private List<String> zipEntryNames(byte[] bytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            List<String> names = new java.util.ArrayList<>();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
            return names;
        }
    }

    private String workbookAudioPath(byte[] packageBytes) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(
                zipEntryBytes(packageBytes, "course-42.xlsm")))) {
            return workbook.getSheet("Content").getRow(1).getCell(6).getStringCellValue();
        }
    }

    private byte[] zipEntryBytes(byte[] bytes, String path) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(path)) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new AssertionError("Missing ZIP entry: " + path);
    }

    private byte[] workbookFromZip(byte[] packageBytes) throws Exception {
        return zipEntryBytes(packageBytes, "course-42.xlsm");
    }

    private byte[] packageWithWorkbook(byte[] workbook) {
        return new CourseExportPackageBuilder().build(42L, workbook, List.of());
    }

    private byte[] removeContentRow(byte[] workbookBytes, int rowIndex) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.getSheet("Content").removeRow(workbook.getSheet("Content").getRow(rowIndex));
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] appendBlankQuestionRow(byte[] workbookBytes) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet content = workbook.getSheet("Content");
            Row source = content.getRow(1);
            Row added = content.createRow(content.getLastRowNum() + 1);
            for (int column = 0; column <= 36; column++) {
                if (source.getCell(column) != null) {
                    added.createCell(column).setCellValue(source.getCell(column).toString());
                }
            }
            added.getCell(8).setCellValue("A newly added question");
            added.getCell(19, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(2);
            added.getCell(10).setCellValue("New answer");
            added.getCell(28).setCellValue(42);
            added.getCell(29).setCellValue(100);
            added.getCell(30).setCellValue(200);
            for (int column = 11; column <= 15; column++) {
                added.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setBlank();
            }
            for (int column = 31; column <= 35; column++) {
                added.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setBlank();
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private RoundTripFixture roundTripFixture(boolean audio, int questionCount) {
        User teacher = new User();
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", 42L);
        course.setTeacher(teacher);
        course.setName("Round trip course");

        CourseRevision revision = new CourseRevision();
        revision.setCourse(course);
        revision.setTeacher(teacher);
        revision.setRevisionNumber(1);
        revision.setStatus(CourseRevisionStatus.DRAFT);
        revision.setName("Round trip course");
        revision.setDescription("Description");
        revision.setType(com.learne.backend.teacher.content.enums.CourseType.COMPREHENSIVE);
        revision.setLevel("A1");

        TopicRevision topic = new TopicRevision();
        ReflectionTestUtils.setField(topic, "id", 100L);
        topic.setCourseRevision(revision);
        topic.setName("Greetings");
        topic.setDescription("Basic greetings");
        topic.setOrderIndex(1);
        Topic sourceTopic = new Topic();
        ReflectionTestUtils.setField(sourceTopic, "id", 1000L);
        topic.setSourceTopic(sourceTopic);
        revision.getTopics().add(topic);

        LessonRevision lesson = new LessonRevision();
        ReflectionTestUtils.setField(lesson, "id", 200L);
        lesson.setTopicRevision(topic);
        lesson.setTeacher(teacher);
        lesson.setTitle("Hello");
        lesson.setDescription("Lesson");
        lesson.setContent("Content");
        lesson.setSkillType(com.learne.backend.teacher.content.enums.SkillType.READING);
        lesson.setOrderIndex(1);
        lesson.setAudioUrl(audio ? "https://cdn/source-audio.mp3" : null);
        lesson.setAudioPublicId(audio ? "source-audio-id" : null);
        Lesson sourceLesson = new Lesson();
        ReflectionTestUtils.setField(sourceLesson, "id", 2000L);
        lesson.setSourceLesson(sourceLesson);
        topic.getLessons().add(lesson);

        List<Question> sourceQuestions = new java.util.ArrayList<>();
        List<QuestionOption> sourceOptions = new java.util.ArrayList<>();
        for (int index = 0; index < questionCount; index++) {
            QuestionRevision question = new QuestionRevision();
            ReflectionTestUtils.setField(question, "id", 300L + index);
            question.setLessonRevision(lesson);
            question.setTeacher(teacher);
            question.setType(index == 0 && questionCount > 1
                    ? com.learne.backend.teacher.content.enums.QuestionType.MULTIPLE_CHOICE
                    : com.learne.backend.teacher.content.enums.QuestionType.SHORT_ANSWER);
            question.setQuestionText(index == 0 ? "Say hello" : "Say hi");
            question.setCorrectAnswer(index == 0 && questionCount > 1 ? null : index == 0 ? "Hello" : "Hi");
            question.setOrderIndex(index + 1);
            Question sourceQuestion = new Question();
            ReflectionTestUtils.setField(sourceQuestion, "id", 3000L + index);
            question.setSourceQuestion(sourceQuestion);
            sourceQuestions.add(sourceQuestion);
            if (index == 0 && questionCount > 1) {
                for (int optionIndex = 0; optionIndex < 2; optionIndex++) {
                    QuestionOptionRevision option = new QuestionOptionRevision();
                    ReflectionTestUtils.setField(option, "id", 400L + optionIndex);
                    option.setQuestionRevision(question);
                    option.setOptionText(optionIndex == 0 ? "Hello" : "Goodbye");
                    option.setCorrect(optionIndex == 0);
                    option.setOrderIndex(optionIndex + 1);
                    QuestionOption sourceOption = new QuestionOption();
                    ReflectionTestUtils.setField(sourceOption, "id", 4000L + optionIndex);
                    option.setSourceOption(sourceOption);
                    sourceOptions.add(sourceOption);
                    question.getOptions().add(option);
                }
            }
            lesson.getQuestions().add(question);
        }

        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseRevisionRepository revisionRepository = mock(CourseRevisionRepository.class);
        when(courseRepository.findByIdAndTeacher(42L, teacher)).thenReturn(Optional.of(course));
        when(revisionRepository.findTopByCourseOrderByRevisionNumberDesc(course)).thenReturn(Optional.of(revision));
        when(revisionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        StorageService storageService = mock(StorageService.class);
        byte[] downloadedAudioBytes = new byte[] {'I', 'D', '3', 1, 2, 3};
        if (audio) {
            when(storageService.downloadLessonAudio("source-audio-id", "https://cdn/source-audio.mp3"))
                    .thenReturn(new StorageDownloadResult("audio/mpeg", downloadedAudioBytes));
            when(storageService.uploadLessonAudio(any()))
                    .thenReturn(new StorageUploadResult("https://cdn/imported-audio.mp3", "imported-audio-id"));
        }
        CourseImportExportService service = service(new CourseImportArchiveReader(),
                new CourseExcelTemplateParser(), new CourseExcelTemplateExporter(), new CourseExportPackageBuilder(),
                new AudioFileValidator(), mock(ClamAvClient.class), storageService, courseRepository,
                revisionRepository);
        return new RoundTripFixture(service, teacher, course, revision, sourceTopic, sourceLesson, sourceQuestions,
                sourceOptions, audio ? downloadedAudioBytes : null, storageService, revisionRepository);
    }

    private static final class RoundTripFixture {
        private final CourseImportExportService service;
        private final User teacher;
        private final Course course;
        private final CourseRevision sourceRevision;
        private final Topic sourceTopic;
        private final Lesson sourceLesson;
        private final List<Question> sourceQuestions;
        private final List<QuestionOption> sourceOptions;
        private final byte[] downloadedAudioBytes;
        private final StorageService storageService;
        private final CourseRevisionRepository revisionRepository;

        private RoundTripFixture(CourseImportExportService service, User teacher, Course course,
                CourseRevision sourceRevision, Topic sourceTopic, Lesson sourceLesson,
                List<Question> sourceQuestions, List<QuestionOption> sourceOptions, byte[] downloadedAudioBytes,
                StorageService storageService,
                CourseRevisionRepository revisionRepository) {
            this.service = service;
            this.teacher = teacher;
            this.course = course;
            this.sourceRevision = sourceRevision;
            this.sourceTopic = sourceTopic;
            this.sourceLesson = sourceLesson;
            this.sourceQuestions = sourceQuestions;
            this.sourceOptions = sourceOptions;
            this.downloadedAudioBytes = downloadedAudioBytes;
            this.storageService = storageService;
            this.revisionRepository = revisionRepository;
        }

        private byte[] export() {
            return service.exportCourse(teacher, 42L);
        }

        private void importPackage(byte[] bytes) {
            service.importCourse(teacher, new MockMultipartFile("file", "course.zip", "application/zip", bytes));
        }

        private CourseRevision savedRevision() {
            ArgumentCaptor<CourseRevision> captor = ArgumentCaptor.forClass(CourseRevision.class);
            verify(revisionRepository).save(captor.capture());
            return captor.getValue();
        }
    }

    private final class ExportFixture {
        private final User teacher;
        private final CourseRevision revision;
        private final CourseExcelTemplateExporter templateExporter;
        private final StorageService storageService;
        private final CourseRepository courseRepository;
        private final CourseRevisionRepository courseRevisionRepository;
        private final CourseImportArchiveReader archiveReader;
        private final CourseExcelTemplateParser templateParser;

        private ExportFixture(User teacher, CourseRevision revision, CourseExcelTemplateExporter templateExporter,
                StorageService storageService, CourseRepository courseRepository,
                CourseRevisionRepository courseRevisionRepository, CourseImportArchiveReader archiveReader,
                CourseExcelTemplateParser templateParser) {
            this.teacher = teacher;
            this.revision = revision;
            this.templateExporter = templateExporter;
            this.storageService = storageService;
            this.courseRepository = courseRepository;
            this.courseRevisionRepository = courseRevisionRepository;
            this.archiveReader = archiveReader;
            this.templateParser = templateParser;
        }

        private CourseImportExportService service(CourseExportPackageBuilder packageBuilder,
                AudioFileValidator audioFileValidator) {
            return service(packageBuilder, audioFileValidator, templateExporter);
        }

        private CourseImportExportService service(CourseExportPackageBuilder packageBuilder,
                AudioFileValidator audioFileValidator, CourseExcelTemplateExporter exporter) {
            return CourseImportExportServiceTests.this.service(archiveReader, templateParser, exporter, packageBuilder,
                    audioFileValidator, mock(ClamAvClient.class), storageService, courseRepository,
                    courseRevisionRepository);
        }
    }

    private CourseImportExportService serviceForReimport(Course course, User teacher, CourseRevision sourceRevision,
            CourseImportModel model) {
        CourseImportArchiveReader archiveReader = mock(CourseImportArchiveReader.class);
        CourseExcelTemplateParser templateParser = mock(CourseExcelTemplateParser.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseRevisionRepository courseRevisionRepository = mock(CourseRevisionRepository.class);
        when(archiveReader.read(any(), any())).thenReturn(new CourseImportPackage(new byte[] {1}, Map.of()));
        when(templateParser.parse(any())).thenReturn(model);
        when(courseRepository.findByIdAndTeacher(42L, teacher)).thenReturn(Optional.of(course));
        when(courseRevisionRepository.findTopByCourseOrderByRevisionNumberDesc(course)).thenReturn(Optional.of(sourceRevision));
        return service(archiveReader, templateParser, mock(CourseExcelTemplateExporter.class),
                new CourseExportPackageBuilder(),
                mock(AudioFileValidator.class), mock(ClamAvClient.class), mock(StorageService.class),
                courseRepository, courseRevisionRepository);
    }

    /** Real transaction proxy around the writer; repositories remain mocks for graph assertions. */
    private CourseImportExportService service(CourseImportArchiveReader reader, CourseExcelTemplateParser parser,
            CourseExcelTemplateExporter exporter, CourseExportPackageBuilder builder, AudioFileValidator validator,
            ClamAvClient scanner, StorageService storage, CourseRepository courses, CourseRevisionRepository revisions) {
        CourseWriteLockService locks = mock(CourseWriteLockService.class);
        lenient().when(locks.teacherCourse(any(), any())).thenAnswer(call ->
                courses.findByIdAndTeacher(call.getArgument(0), call.getArgument(1)).orElseThrow());
        CourseImportPersistenceService target = new CourseImportPersistenceService(courses, revisions, locks,
                org.mockito.Mockito.mock(com.learne.backend.teacher.content.submission.SubmissionGuard.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        CourseImportPersistenceService writer = (CourseImportPersistenceService) proxy.getProxy();
        CourseImportPreparationService preparation = new CourseImportPreparationService(reader, parser, validator,
                scanner, storage, writer, cleanup);
        return new CourseImportExportService(preparation, writer, exporter, builder, validator, storage, courses, revisions, cleanup);
    }

    private CourseRevision sourceRevision(Course course, User teacher) {
        CourseRevision revision = new CourseRevision();
        revision.setCourse(course);
        revision.setTeacher(teacher);
        revision.setRevisionNumber(1);
        revision.setStatus(CourseRevisionStatus.DRAFT);
        TopicRevision topic = new TopicRevision();
        ReflectionTestUtils.setField(topic, "id", 100L);
        topic.setCourseRevision(revision);
        revision.getTopics().add(topic);
        return revision;
    }

    private LessonRevision lessonRevision(TopicRevision topic, Long id) {
        LessonRevision lesson = new LessonRevision();
        ReflectionTestUtils.setField(lesson, "id", id);
        lesson.setTopicRevision(topic);
        return lesson;
    }

    private QuestionRevision questionRevision(LessonRevision lesson, Long id) {
        QuestionRevision question = new QuestionRevision();
        ReflectionTestUtils.setField(question, "id", id);
        question.setLessonRevision(lesson);
        return question;
    }

    private QuestionOptionRevision optionRevision(QuestionRevision question, Long id) {
        QuestionOptionRevision option = new QuestionOptionRevision();
        ReflectionTestUtils.setField(option, "id", id);
        option.setQuestionRevision(question);
        return option;
    }

    private CourseImportModel reimportModel(List<TopicImportModel> topics) {
        return new CourseImportModel(42L, "Imported English A1", "Description", "COMPREHENSIVE", "A1", 1, topics);
    }

    private QuestionImportModel shortAnswerQuestion(Long id) {
        return new QuestionImportModel(id, 1, "SHORT_ANSWER", "Say hello", null, "Hello", "greetings", "easy", 5,
                List.of());
    }

    private MockMultipartFile multipartFile() {
        return new MockMultipartFile("file", "course.zip", "application/zip", new byte[] {1});
    }

    private byte[] revisionWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            row(workbook.createSheet("_metadata"), 0, "_template_version", "v1");
            row(workbook.getSheet("_metadata"), 1, "_template_type", "flat_course_content");
            Sheet course = workbook.createSheet("Course");
            row(course, 0, "course_name", "Imported English A1");
            row(course, 1, "course_description", "Description");
            row(course, 2, "course_type", "COMPREHENSIVE");
            row(course, 3, "course_level", "A1");
            Sheet content = workbook.createSheet("Content");
            String[] headers = {"topic_title", "topic_description", "lesson_title", "lesson_description",
                    "lesson_content", "skill_type", "audio_file", "question_type", "question_text", "explanation",
                    "correct_answer", "option_a", "option_b", "option_c", "option_d", "correct_option",
                    "_row_fingerprint", "_course_id", "_topic_id", "_lesson_id", "_question_id"};
            row(content, 0, headers);
            contentRow(content, 1, "Say hello", "Hello", 300L);
            contentRow(content, 2, "Say hi", "Hi", 301L);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void row(Sheet sheet, int rowIndex, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }

    private void contentRow(Sheet sheet, int rowIndex, String question, String answer, long questionId) {
        String[] values = new String[21];
        values[0] = "Greetings";
        values[1] = "Basic greetings";
        values[2] = "Hello";
        values[3] = "Lesson";
        values[4] = "Content";
        values[5] = "READING";
        values[7] = "SHORT_ANSWER";
        values[8] = question;
        values[10] = answer;
        values[17] = "42";
        values[18] = "100";
        values[19] = "200";
        values[20] = Long.toString(questionId);
        row(sheet, rowIndex, values);
    }

    private CourseImportModel courseImportModelWithAudio() {
        return courseImportModel(null, "audio/lesson.mp3");
    }

    private CourseImportModel courseImportModel(Long courseId, String audioFile) {
        QuestionImportModel question = new QuestionImportModel(null, 1, "SHORT_ANSWER", "Say hello", null,
                "Hello", "greetings", "easy", 5, List.of());
        LessonImportModel lesson = new LessonImportModel(null, 1, "Hello", "Greeting lesson", "Practice hello",
                "READING", 10, false, audioFile, List.of(question));
        TopicImportModel topic = new TopicImportModel(null, 1, "Greetings", "Basic greetings", List.of(lesson));
        return new CourseImportModel(courseId, "Imported English A1", "Course imported from ZIP", "COMPREHENSIVE",
                "A1", 1, List.of(topic));
    }
}
