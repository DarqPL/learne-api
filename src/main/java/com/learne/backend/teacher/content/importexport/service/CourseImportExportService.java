package com.learne.backend.teacher.content.importexport.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.dto.StorageDownloadResult;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.audio.service.LessonAudioCleanupService;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.QuestionRevision;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.importexport.archive.CourseImportPackage;
import com.learne.backend.teacher.content.importexport.dto.CourseImportResponse;
import com.learne.backend.teacher.content.importexport.security.AudioFileValidator;
import com.learne.backend.teacher.content.importexport.template.CourseExcelTemplateExporter;
import com.learne.backend.teacher.content.importexport.template.CourseExportPackageBuilder;
import com.learne.backend.teacher.content.importexport.template.ExportedAudioFile;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import com.learne.backend.teacher.content.service.CourseWriteOutcome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CourseImportExportService {
    private static final Logger log = LoggerFactory.getLogger(CourseImportExportService.class);
    private final CourseImportPreparationService preparation;
    private final CourseImportPersistenceService persistence;
    private final CourseExcelTemplateExporter templateExporter;
    private final CourseExportPackageBuilder exportPackageBuilder;
    private final AudioFileValidator audioFileValidator;
    private final StorageService storageService;
    private final CourseRepository courseRepository;
    private final CourseRevisionRepository courseRevisionRepository;
    private final LessonAudioCleanupService cleanup;

    public CourseImportExportService(CourseImportPreparationService preparation, CourseImportPersistenceService persistence,
            CourseExcelTemplateExporter templateExporter, CourseExportPackageBuilder exportPackageBuilder,
            AudioFileValidator audioFileValidator, StorageService storageService, CourseRepository courseRepository,
            CourseRevisionRepository courseRevisionRepository, LessonAudioCleanupService cleanup) {
        this.preparation = preparation;
        this.persistence = persistence;
        this.templateExporter = templateExporter;
        this.exportPackageBuilder = exportPackageBuilder;
        this.audioFileValidator = audioFileValidator;
        this.storageService = storageService;
        this.courseRepository = courseRepository;
        this.courseRevisionRepository = courseRevisionRepository;
        this.cleanup = cleanup;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public CourseImportResponse importCourse(User teacher, MultipartFile file) {
        PreparedCourseImport prepared = preparation.prepare(teacher, file);
        CourseWriteOutcome outcome = new CourseWriteOutcome();
        try {
            return persistence.persist(teacher, prepared, outcome);
        } catch (RuntimeException exception) {
            // A thrown proxy call can also be a post-commit failure. Never infer rollback from it.
            if (outcome.rolledBack()) {
                try {
                    cleanup.compensate(prepared.operationId(), prepared.model().courseId(), prepared.uploadedAssets().stream()
                            .map(asset -> new LessonAudioState(asset.url(), asset.publicId())).toList(), true, outcome);
                } catch (RuntimeException cleanupFailure) {
                    log.warn("Import rollback compensation failed for course {} ({}); retaining assets",
                            prepared.model().courseId(), cleanupFailure.getClass().getSimpleName());
                }
            } else {
                log.warn("Import persistence threw without confirmed rollback for course {}; retaining {} assets",
                        prepared.model().courseId(), prepared.uploadedAssets().size());
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public byte[] exportCourse(User teacher, Long courseId) {
        Course course = courseRepository.findByIdAndTeacher(courseId, teacher)
                .orElseThrow(() -> notFound("Không tìm thấy khóa học."));
        CourseRevision revision = courseRevisionRepository.findTopByCourseOrderByRevisionNumberDesc(course)
                .filter(source -> source.getStatus() == CourseRevisionStatus.DRAFT
                        || source.getStatus() == CourseRevisionStatus.REJECTED
                        || source.getStatus() == CourseRevisionStatus.PENDING_REVIEW
                        || source.getStatus() == CourseRevisionStatus.APPROVED)
                .orElseThrow(() -> notFound("Không tìm thấy phiên bản khóa học."));
        validateExportableRevision(course, revision);
        Map<Long, String> audioPaths = new HashMap<>();
        List<ExportedAudioFile> audioFiles = collectAudio(revision, audioPaths);
        byte[] workbook = templateExporter.exportCourse(revision, audioPaths);
        return exportPackageBuilder.build(courseId, workbook, audioFiles);
    }

    private void validateExportableRevision(Course course, CourseRevision revision) {
        if (revision.getTopics().isEmpty()) {
            throw badRequest("Khóa học phải có ít nhất một chủ đề: " + course.getName());
        }
        for (TopicRevision topic : revision.getTopics()) {
            if (topic.getLessons().isEmpty()) {
                throw badRequest("Chủ đề phải có ít nhất một bài học: " + topic.getName());
            }
            for (LessonRevision lesson : topic.getLessons()) {
                if (lesson.getQuestions().isEmpty()) {
                    throw badRequest("Bài học phải có ít nhất một câu hỏi: " + lesson.getTitle());
                }
                for (QuestionRevision question : lesson.getQuestions()) {
                    if (question.getOptions().size() > 4) {
                        throw badRequest("Câu hỏi vượt quá số lượng tối đa 4 lựa chọn: " + question.getQuestionText());
                    }
                }
            }
        }
    }

    private List<ExportedAudioFile> collectAudio(CourseRevision revision, Map<Long, String> audioPaths) {
        List<ExportedAudioFile> files = new ArrayList<>();
        Set<String> usedPaths = new HashSet<>();
        for (TopicRevision topic : revision.getTopics()) {
            for (LessonRevision lesson : topic.getLessons()) {
                if (!hasText(lesson.getAudioPublicId()) && !hasText(lesson.getAudioUrl())) {
                    continue;
                }
                if (lesson.getId() == null) {
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "Bài học có audio nhưng thiếu ID: " + lesson.getTitle());
                }
                StorageDownloadResult download;
                try {
                    download = storageService.downloadLessonAudio(lesson.getAudioPublicId(), lesson.getAudioUrl());
                } catch (RuntimeException exception) {
                    throw audioDownloadFailure(lesson, HttpStatus.BAD_GATEWAY, exception.getMessage());
                }
                if (download == null) {
                    throw audioDownloadFailure(lesson, HttpStatus.BAD_GATEWAY, "Không nhận được dữ liệu audio.");
                }
                CourseImportPackage.ImportFile file = new CourseImportPackage.ImportFile(
                        "audio", download.contentType(), download.bytes());
                try {
                    audioFileValidator.validate(file);
                } catch (ApiException exception) {
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "Dữ liệu audio đã lưu không hợp lệ cho bài học " + lesson.getId()
                                    + " (" + lesson.getTitle() + "): " + exception.getMessage());
                }
                String path = uniqueAudioPath(lesson, download.contentType(), usedPaths);
                audioPaths.put(lesson.getId(), path);
                files.add(new ExportedAudioFile(path, download.contentType(), download.bytes()));
            }
        }
        return files;
    }

    private ApiException audioDownloadFailure(LessonRevision lesson, HttpStatus status, String detail) {
        String suffix = hasText(detail) ? ": " + detail : ".";
        return new ApiException(status,
                "Không thể tải audio bài học " + lesson.getId() + " (" + lesson.getTitle() + ")" + suffix);
    }

    private String uniqueAudioPath(LessonRevision lesson, String contentType, Set<String> usedPaths) {
        String extension = switch (contentType) {
            case "audio/mpeg" -> "mp3";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mp4" -> "m4a";
            case "audio/ogg" -> "ogg";
            default -> "bin";
        };
        String slug = lesson.getTitle() == null ? "audio"
                : lesson.getTitle().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            slug = "audio";
        }
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        String base = "audio/lesson-" + lesson.getId() + "-" + slug;
        String path = base + "." + extension;
        int suffix = 2;
        while (!usedPaths.add(path)) {
            path = base + "-" + suffix++ + "." + extension;
        }
        return path;
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private ApiException notFound(String message) { return new ApiException(HttpStatus.NOT_FOUND, message); }
}
