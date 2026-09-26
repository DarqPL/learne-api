package com.learne.backend.teacher.content;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learne.backend.domain.auth.User;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.importexport.model.CourseImportModel;
import com.learne.backend.teacher.content.importexport.model.LessonImportModel;
import com.learne.backend.teacher.content.importexport.model.QuestionImportModel;
import com.learne.backend.teacher.content.importexport.model.TopicImportModel;
import com.learne.backend.teacher.content.importexport.security.AudioFileValidator;
import com.learne.backend.teacher.content.importexport.security.ClamAvClient;
import com.learne.backend.teacher.content.importexport.service.CourseImportExportService;
import com.learne.backend.teacher.content.importexport.template.CourseExcelTemplateExporter;
import com.learne.backend.teacher.content.importexport.template.CourseExcelTemplateParser;
import com.learne.backend.teacher.content.importexport.zip.CourseImportPackage;
import com.learne.backend.teacher.content.importexport.zip.CourseImportZipReader;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class CourseImportExportServiceTests {
    @Test
    void importCourseDeletesUploadedAudioWhenPersistenceFailsAfterUpload() {
        CourseImportZipReader zipReader = mock(CourseImportZipReader.class);
        CourseExcelTemplateParser templateParser = mock(CourseExcelTemplateParser.class);
        CourseExcelTemplateExporter templateExporter = mock(CourseExcelTemplateExporter.class);
        AudioFileValidator audioFileValidator = mock(AudioFileValidator.class);
        ClamAvClient clamAvClient = mock(ClamAvClient.class);
        StorageService storageService = mock(StorageService.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseRevisionRepository courseRevisionRepository = mock(CourseRevisionRepository.class);
        CourseImportExportService service = new CourseImportExportService(zipReader, templateParser, templateExporter,
                audioFileValidator, clamAvClient, storageService, courseRepository, courseRevisionRepository);
        RuntimeException persistenceFailure = new RuntimeException("revision save failed");

        CourseImportPackage importPackage = new CourseImportPackage(
                new byte[] {1},
                Map.of("audio/lesson.mp3", new CourseImportPackage.ImportFile(
                        "audio/lesson.mp3", "audio/mpeg", new byte[] {1, 2, 3})));
        when(zipReader.read(any())).thenReturn(importPackage);
        when(templateParser.parse(importPackage.workbook())).thenReturn(courseImportModelWithAudio());
        when(storageService.uploadLessonAudio(any())).thenReturn(new StorageUploadResult("https://cdn/audio.mp3", "audio-public-id"));
        when(courseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0, Course.class));
        doThrow(persistenceFailure).when(courseRevisionRepository).save(any());

        assertThatThrownBy(() -> service.importCourse(new User(), new MockMultipartFile(
                "file", "course.zip", "application/zip", new byte[] {1})))
                .isSameAs(persistenceFailure);

        verify(storageService).delete("audio-public-id", StorageResourceType.VIDEO);
    }

    @Test
    void importCourseRegistersRollbackCleanupForUploadedAudio() {
        CourseImportZipReader zipReader = mock(CourseImportZipReader.class);
        CourseExcelTemplateParser templateParser = mock(CourseExcelTemplateParser.class);
        CourseExcelTemplateExporter templateExporter = mock(CourseExcelTemplateExporter.class);
        AudioFileValidator audioFileValidator = mock(AudioFileValidator.class);
        ClamAvClient clamAvClient = mock(ClamAvClient.class);
        StorageService storageService = mock(StorageService.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseRevisionRepository courseRevisionRepository = mock(CourseRevisionRepository.class);
        CourseImportExportService service = new CourseImportExportService(zipReader, templateParser, templateExporter,
                audioFileValidator, clamAvClient, storageService, courseRepository, courseRevisionRepository);

        CourseImportPackage importPackage = new CourseImportPackage(
                new byte[] {1},
                Map.of("audio/lesson.mp3", new CourseImportPackage.ImportFile(
                        "audio/lesson.mp3", "audio/mpeg", new byte[] {1, 2, 3})));
        when(zipReader.read(any())).thenReturn(importPackage);
        when(templateParser.parse(importPackage.workbook())).thenReturn(courseImportModelWithAudio());
        when(storageService.uploadLessonAudio(any())).thenReturn(new StorageUploadResult("https://cdn/audio.mp3", "audio-public-id"));
        when(courseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0, Course.class));
        when(courseRevisionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.importCourse(new User(), new MockMultipartFile("file", "course.zip", "application/zip", new byte[] {1}));

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(storageService).delete("audio-public-id", StorageResourceType.VIDEO);
    }

    private CourseImportModel courseImportModelWithAudio() {
        QuestionImportModel question = new QuestionImportModel(null, 1, "SHORT_ANSWER", "Say hello", null,
                "Hello", "greetings", "easy", 5, List.of());
        LessonImportModel lesson = new LessonImportModel(null, 1, "Hello", "Greeting lesson", "Practice hello",
                "READING", 10, false, "audio/lesson.mp3", List.of(question));
        TopicImportModel topic = new TopicImportModel(null, 1, "Greetings", "Basic greetings", List.of(lesson));
        return new CourseImportModel(null, "Imported English A1", "Course imported from ZIP", "COMPREHENSIVE",
                "A1", 1, List.of(topic));
    }
}
