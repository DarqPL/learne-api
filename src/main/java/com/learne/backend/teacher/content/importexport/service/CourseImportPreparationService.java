package com.learne.backend.teacher.content.importexport.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.audio.service.LessonAudioCleanupService;
import com.learne.backend.teacher.content.importexport.archive.CourseImportArchiveReader;
import com.learne.backend.teacher.content.importexport.archive.CourseImportPackage;
import com.learne.backend.teacher.content.importexport.model.CourseImportModel;
import com.learne.backend.teacher.content.importexport.model.LessonImportModel;
import com.learne.backend.teacher.content.importexport.model.TopicImportModel;
import com.learne.backend.teacher.content.importexport.security.AudioFileValidator;
import com.learne.backend.teacher.content.importexport.security.ClamAvClient;
import com.learne.backend.teacher.content.importexport.template.CourseExcelTemplateParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CourseImportPreparationService {
    private static final Logger log = LoggerFactory.getLogger(CourseImportPreparationService.class);
    private final CourseImportArchiveReader archiveReader;
    private final CourseExcelTemplateParser parser;
    private final AudioFileValidator validator;
    private final ClamAvClient scanner;
    private final StorageService storage;
    private final CourseImportPersistenceService persistence;
    private final LessonAudioCleanupService cleanup;

    public CourseImportPreparationService(CourseImportArchiveReader archiveReader, CourseExcelTemplateParser parser,
            AudioFileValidator validator, ClamAvClient scanner, StorageService storage,
            CourseImportPersistenceService persistence, LessonAudioCleanupService cleanup) {
        this.archiveReader = archiveReader;
        this.parser = parser;
        this.validator = validator;
        this.scanner = scanner;
        this.storage = storage;
        this.persistence = persistence;
        this.cleanup = cleanup;
    }

    @Transactional(propagation = Propagation.NEVER)
    public PreparedCourseImport prepare(User teacher, MultipartFile file) {
        CourseImportPackage archive = archiveReader.read(bytes(file), file.getOriginalFilename());
        CourseImportModel model = parser.parse(archive.workbook());
        Map<String, CourseImportPackage.ImportFile> audioFiles = referencedAudio(archive, model);
        // Finish validation and ownership checks before spending any provider uploads.
        audioFiles.values().forEach(validator::validate);
        Long expectedRevision = persistence.preflight(teacher, model);
        Map<String, StorageUploadResult> uploadedByPath = new LinkedHashMap<>();
        List<StorageUploadResult> uploaded = new ArrayList<>();
        try {
            for (var entry : audioFiles.entrySet()) {
                CourseImportPackage.ImportFile audio = entry.getValue();
                scanner.assertClean(audio.path(), audio.bytes());
                StorageUploadResult result = storage.uploadLessonAudio(new ImportMultipartFile(audio));
                uploaded.add(result);
                uploadedByPath.put(entry.getKey(), result);
            }
            return new PreparedCourseImport(model, expectedRevision, uploadedByPath, uploaded);
        } catch (RuntimeException exception) {
            // No persistence attempt has started; still require provenance + global reference proof.
            try {
                cleanup.tryCleanup(model.courseId(), uploaded.stream()
                        .map(asset -> new LessonAudioState(asset.url(), asset.publicId())).toList());
            } catch (RuntimeException cleanupFailure) {
                log.warn("Import preparation compensation failed for course {} ({}); retaining assets",
                        model.courseId(), cleanupFailure.getClass().getSimpleName());
            }
            throw exception;
        }
    }

    private Map<String, CourseImportPackage.ImportFile> referencedAudio(CourseImportPackage archive, CourseImportModel model) {
        Map<String, CourseImportPackage.ImportFile> referenced = new LinkedHashMap<>();
        for (TopicImportModel topic : model.topics()) {
            for (LessonImportModel lesson : topic.lessons()) {
                if (lesson.audioFile() == null || lesson.audioFile().isBlank()) {
                    continue;
                }
                String path = lesson.audioFile().trim();
                if (!path.startsWith("audio/")) {
                    throw badRequest("File audio phải nằm trong thư mục audio/: " + path);
                }
                CourseImportPackage.ImportFile audio = archive.audioFiles().get(path);
                if (audio == null) {
                    throw badRequest("Không tìm thấy file audio được tham chiếu: " + path);
                }
                referenced.putIfAbsent(path, audio);
            }
        }
        for (String path : archive.audioFiles().keySet()) {
            if (!referenced.containsKey(path)) {
                throw badRequest("File audio không được tham chiếu trong Excel: " + path);
            }
        }
        return referenced;
    }

    private byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw badRequest("Gói nhập không hợp lệ");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw badRequest("Gói nhập không hợp lệ");
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private static final class ImportMultipartFile implements MultipartFile {
        private final CourseImportPackage.ImportFile file;

        private ImportMultipartFile(CourseImportPackage.ImportFile file) { this.file = file; }
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return file.path(); }
        @Override public String getContentType() { return file.contentType(); }
        @Override public boolean isEmpty() { return file.bytes().length == 0; }
        @Override public long getSize() { return file.bytes().length; }
        @Override public byte[] getBytes() { return file.bytes(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(file.bytes()); }
        @Override public void transferTo(java.io.File dest) throws IOException {
            throw new UnsupportedOperationException("Import audio files are uploaded from memory.");
        }
    }
}
