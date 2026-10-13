package com.learne.backend.teacher.content.importexport.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.dto.StorageResourceType;
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
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.enums.SkillType;
import com.learne.backend.teacher.content.importexport.dto.CourseImportResponse;
import com.learne.backend.teacher.content.importexport.model.CourseImportModel;
import com.learne.backend.teacher.content.importexport.model.LessonImportModel;
import com.learne.backend.teacher.content.importexport.model.OptionImportModel;
import com.learne.backend.teacher.content.importexport.model.QuestionImportModel;
import com.learne.backend.teacher.content.importexport.model.TopicImportModel;
import com.learne.backend.teacher.content.importexport.security.AudioFileValidator;
import com.learne.backend.teacher.content.importexport.security.ClamAvClient;
import com.learne.backend.teacher.content.importexport.template.CourseExcelTemplateExporter;
import com.learne.backend.teacher.content.importexport.template.CourseExcelTemplateParser;
import com.learne.backend.teacher.content.importexport.template.CourseExportPackageBuilder;
import com.learne.backend.teacher.content.importexport.template.ExportedAudioFile;
import com.learne.backend.teacher.content.importexport.archive.CourseImportArchiveReader;
import com.learne.backend.teacher.content.importexport.archive.CourseImportPackage;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CourseImportExportService {
    private static final Logger log = LoggerFactory.getLogger(CourseImportExportService.class);

    private final CourseImportArchiveReader archiveReader;
    private final CourseExcelTemplateParser templateParser;
    private final CourseExcelTemplateExporter templateExporter;
    private final CourseExportPackageBuilder exportPackageBuilder;
    private final AudioFileValidator audioFileValidator;
    private final ClamAvClient clamAvClient;
    private final StorageService storageService;
    private final CourseRepository courseRepository;
    private final CourseRevisionRepository courseRevisionRepository;

    public CourseImportExportService(CourseImportArchiveReader archiveReader,
                                       CourseExcelTemplateParser templateParser,
                                       CourseExcelTemplateExporter templateExporter,
                                       CourseExportPackageBuilder exportPackageBuilder,
                                       AudioFileValidator audioFileValidator,
                                      ClamAvClient clamAvClient,
                                      StorageService storageService,
                                     CourseRepository courseRepository,
                                     CourseRevisionRepository courseRevisionRepository) {
        this.archiveReader = archiveReader;
        this.templateParser = templateParser;
        this.templateExporter = templateExporter;
        this.exportPackageBuilder = exportPackageBuilder;
        this.audioFileValidator = audioFileValidator;
        this.clamAvClient = clamAvClient;
        this.storageService = storageService;
        this.courseRepository = courseRepository;
        this.courseRevisionRepository = courseRevisionRepository;
    }

    @Transactional
    public CourseImportResponse importCourse(User teacher, MultipartFile file) {
        CourseImportPackage importPackage = archiveReader.read(bytes(file), file.getOriginalFilename());
        CourseImportModel model = templateParser.parse(importPackage.workbook());
        if (model.courseId() != null) {
            return reimportExistingCourse(teacher, importPackage, model);
        }
        validateNewCourseModel(model);
        validateAudioReferences(importPackage, model);
        List<String> uploadedAudioPublicIds = new ArrayList<>();
        registerRollbackAudioCleanup(uploadedAudioPublicIds);

        try {
            Course course = new Course();
            course.setTeacher(teacher);
            course.setName(model.name().trim());
            course.setDescription(model.description());
            course.setType(enumValue(CourseType.class, model.type(), "course_type"));
            course.setLevel(model.level().trim());
            course.setOrderIndex(valueOrZero(model.orderIndex()));
            course.setStatus(ContentStatus.DRAFT);
            populateCourse(importPackage, model, course, teacher, uploadedAudioPublicIds);
            Course savedCourse = courseRepository.save(course);

            CourseRevision revision = new CourseRevision();
            revision.setCourse(savedCourse);
            revision.setTeacher(teacher);
            revision.setRevisionNumber(1);
            revision.setStatus(CourseRevisionStatus.DRAFT);
            applyRevisionMetadata(revision, model);
            applyRevisionPricingMetadata(revision, savedCourse);
            populateRevisionFromCourse(savedCourse, revision);

            CourseRevision savedRevision = courseRevisionRepository.save(revision);
            return new CourseImportResponse(savedCourse.getId(), savedRevision.getId(), CourseRevisionStatus.DRAFT.name(), List.of());
        } catch (RuntimeException exception) {
            cleanupUploadedAudio(uploadedAudioPublicIds);
            uploadedAudioPublicIds.clear();
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public byte[] exportCourse(User teacher, Long courseId) {
        Course course = courseRepository.findByIdAndTeacher(courseId, teacher)
                .orElseThrow(() -> notFound("Không tìm thấy khóa học."));
        CourseRevision revision = latestUpdatableSourceRevision(course)
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
                        throw badRequest("Câu hỏi vượt quá số lượng tối đa 4 lựa chọn: "
                                + question.getQuestionText());
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
                } catch (ApiException exception) {
                    throw audioDownloadFailure(lesson, HttpStatus.BAD_GATEWAY, exception.getMessage());
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
                : lesson.getTitle().toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
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

    private CourseImportResponse reimportExistingCourse(User teacher, CourseImportPackage importPackage,
            CourseImportModel model) {
        Course course = courseRepository.findByIdAndTeacher(model.courseId(), teacher)
                .orElseThrow(() -> notFound("Không tìm thấy khóa học."));
        CourseRevision sourceRevision = latestUpdatableSourceRevision(course)
                .orElseThrow(() -> notFound("Không tìm thấy phiên bản khóa học để cập nhật."));
        CourseRevision revision = sourceRevision.getStatus() == CourseRevisionStatus.APPROVED
                || sourceRevision.getStatus() == CourseRevisionStatus.PENDING_REVIEW
                ? newDraftRevisionFrom(course, teacher)
                : sourceRevision;
        validateExistingCourseModel(model, sourceRevision);
        validateAudioReferences(importPackage, model);

        List<String> uploadedAudioPublicIds = new ArrayList<>();
        registerRollbackAudioCleanup(uploadedAudioPublicIds);
        Map<Long, TopicRevision> existingTopics = topicsById(sourceRevision);
        Map<Long, LessonRevision> existingLessons = lessonsById(sourceRevision);
        Map<Long, QuestionRevision> existingQuestions = questionsById(sourceRevision);
        Map<Long, QuestionOptionRevision> existingOptions = optionsById(sourceRevision);
        try {
            applyRevisionMetadata(revision, model);
            applyRevisionPricingMetadata(revision, sourceRevision);
            revision.getTopics().clear();
            populateRevision(importPackage, model, revision, teacher, uploadedAudioPublicIds,
                    existingTopics, existingLessons, existingQuestions, existingOptions);
            if (sourceRevision.getStatus() == CourseRevisionStatus.PENDING_REVIEW) {
                sourceRevision.setStatus(CourseRevisionStatus.SUPERSEDED);
                course.setStatus(ContentStatus.DRAFT);
            }
            CourseRevision savedRevision = courseRevisionRepository.save(revision);
            return new CourseImportResponse(course.getId(), savedRevision.getId(), savedRevision.getStatus().name(), List.of());
        } catch (RuntimeException exception) {
            cleanupUploadedAudio(uploadedAudioPublicIds);
            uploadedAudioPublicIds.clear();
            throw exception;
        }
    }

    private void registerRollbackAudioCleanup(List<String> uploadedAudioPublicIds) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    cleanupUploadedAudio(uploadedAudioPublicIds);
                }
            }
        });
    }

    private void populateRevision(CourseImportPackage importPackage, CourseImportModel model, CourseRevision revision,
            User teacher, List<String> uploadedAudioPublicIds, Map<Long, TopicRevision> existingTopics,
            Map<Long, LessonRevision> existingLessons, Map<Long, QuestionRevision> existingQuestions,
            Map<Long, QuestionOptionRevision> existingOptions) {
        for (TopicImportModel topicModel : model.topics()) {
            TopicRevision topic = new TopicRevision();
            topic.setCourseRevision(revision);
            TopicRevision existingTopic = topicModel.topicId() == null ? null : existingTopics.get(topicModel.topicId());
            if (existingTopic != null) {
                topic.setSourceTopic(existingTopic.getSourceTopic());
            }
            topic.setName(topicModel.title().trim());
            topic.setDescription(topicModel.description());
            topic.setOrderIndex(topicModel.orderIndex());
            revision.getTopics().add(topic);

            for (LessonImportModel lessonModel : topicModel.lessons()) {
                LessonRevision lesson = new LessonRevision();
                lesson.setTopicRevision(topic);
                LessonRevision existingLesson = lessonModel.lessonId() == null ? null : existingLessons.get(lessonModel.lessonId());
                if (existingLesson != null) {
                    lesson.setSourceLesson(existingLesson.getSourceLesson());
                }
                lesson.setTeacher(teacher);
                lesson.setTitle(lessonModel.title().trim());
                lesson.setDescription(lessonModel.description());
                lesson.setContent(lessonModel.content());
                lesson.setSkillType(enumValue(SkillType.class, lessonModel.skillType(), "skill_type"));
                lesson.setOrderIndex(lessonModel.orderIndex());
                lesson.setXpReward(valueOrZero(lessonModel.xpReward()));
                lesson.setPlacementTest(lessonModel.placementTest());
                applyAudio(importPackage, lessonModel, lesson, uploadedAudioPublicIds, existingLessons);
                topic.getLessons().add(lesson);

                for (QuestionImportModel questionModel : lessonModel.questions()) {
                    QuestionRevision question = new QuestionRevision();
                    question.setLessonRevision(lesson);
                    QuestionRevision existingQuestion = questionModel.questionId() == null
                            ? null : existingQuestions.get(questionModel.questionId());
                    if (existingQuestion != null) {
                        question.setSourceQuestion(existingQuestion.getSourceQuestion());
                    }
                    question.setTeacher(teacher);
                    question.setType(enumValue(QuestionType.class, questionModel.type(), "question_type"));
                    question.setQuestionText(questionModel.questionText().trim());
                    question.setExplanation(questionModel.explanation());
                    question.setCorrectAnswer(questionModel.correctAnswer());
                    question.setKnowledgePoint(questionModel.knowledgePoint());
                    question.setOrderIndex(questionModel.orderIndex());
                    question.setDifficulty(questionModel.difficulty());
                    question.setXpReward(valueOrZero(questionModel.xpReward()));
                    lesson.getQuestions().add(question);

                    for (OptionImportModel optionModel : questionModel.options()) {
                        QuestionOptionRevision option = new QuestionOptionRevision();
                        option.setQuestionRevision(question);
                        QuestionOptionRevision existingOption = optionModel.optionId() == null
                                ? null : existingOptions.get(optionModel.optionId());
                        if (existingOption != null) {
                            option.setSourceOption(existingOption.getSourceOption());
                        }
                        option.setOptionText(optionModel.text());
                        option.setCorrect(optionModel.correct());
                        option.setOrderIndex(optionModel.orderIndex());
                        question.getOptions().add(option);
                    }
                }
            }
        }
    }

    private void populateCourse(CourseImportPackage importPackage, CourseImportModel model, Course course,
            User teacher, List<String> uploadedAudioPublicIds) {
        for (TopicImportModel topicModel : model.topics()) {
            Topic topic = new Topic();
            topic.setCourse(course);
            topic.setName(topicModel.title().trim());
            topic.setDescription(topicModel.description());
            topic.setOrderIndex(topicModel.orderIndex());
            course.getTopics().add(topic);

            for (LessonImportModel lessonModel : topicModel.lessons()) {
                Lesson lesson = new Lesson();
                lesson.setTopic(topic);
                lesson.setTeacher(teacher);
                lesson.setTitle(lessonModel.title().trim());
                lesson.setDescription(lessonModel.description());
                lesson.setContent(lessonModel.content());
                lesson.setSkillType(enumValue(SkillType.class, lessonModel.skillType(), "skill_type"));
                lesson.setOrderIndex(lessonModel.orderIndex());
                lesson.setXpReward(valueOrZero(lessonModel.xpReward()));
                lesson.setPlacementTest(lessonModel.placementTest());
                lesson.setStatus(ContentStatus.DRAFT);
                applyAudio(importPackage, lessonModel, lesson, uploadedAudioPublicIds);
                topic.getLessons().add(lesson);

                for (QuestionImportModel questionModel : lessonModel.questions()) {
                    Question question = new Question();
                    question.setLesson(lesson);
                    question.setTeacher(teacher);
                    question.setType(enumValue(QuestionType.class, questionModel.type(), "question_type"));
                    question.setQuestionText(questionModel.questionText().trim());
                    question.setExplanation(questionModel.explanation());
                    question.setCorrectAnswer(questionModel.correctAnswer());
                    question.setKnowledgePoint(questionModel.knowledgePoint());
                    question.setOrderIndex(questionModel.orderIndex());
                    question.setDifficulty(questionModel.difficulty());
                    question.setXpReward(valueOrZero(questionModel.xpReward()));
                    question.setStatus(ContentStatus.DRAFT);
                    lesson.getQuestions().add(question);

                    for (OptionImportModel optionModel : questionModel.options()) {
                        QuestionOption option = new QuestionOption();
                        option.setQuestion(question);
                        option.setOptionText(optionModel.text());
                        option.setCorrect(optionModel.correct());
                        option.setOrderIndex(optionModel.orderIndex());
                        question.getOptions().add(option);
                    }
                }
            }
        }
    }

    private void populateRevisionFromCourse(Course course, CourseRevision revision) {
        for (Topic topic : course.getTopics()) {
            TopicRevision topicRevision = new TopicRevision();
            topicRevision.setCourseRevision(revision);
            topicRevision.setSourceTopic(topic);
            topicRevision.setName(topic.getName());
            topicRevision.setDescription(topic.getDescription());
            topicRevision.setOrderIndex(topic.getOrderIndex());
            revision.getTopics().add(topicRevision);

            for (Lesson lesson : topic.getLessons()) {
                LessonRevision lessonRevision = new LessonRevision();
                lessonRevision.setTopicRevision(topicRevision);
                lessonRevision.setSourceLesson(lesson);
                lessonRevision.setTeacher(lesson.getTeacher());
                lessonRevision.setTitle(lesson.getTitle());
                lessonRevision.setDescription(lesson.getDescription());
                lessonRevision.setContent(lesson.getContent());
                lessonRevision.setAudioUrl(lesson.getAudioUrl());
                lessonRevision.setAudioPublicId(lesson.getAudioPublicId());
                lessonRevision.setSkillType(lesson.getSkillType());
                lessonRevision.setOrderIndex(lesson.getOrderIndex());
                lessonRevision.setXpReward(lesson.getXpReward());
                lessonRevision.setPlacementTest(lesson.isPlacementTest());
                topicRevision.getLessons().add(lessonRevision);

                for (Question question : lesson.getQuestions()) {
                    QuestionRevision questionRevision = new QuestionRevision();
                    questionRevision.setLessonRevision(lessonRevision);
                    questionRevision.setSourceQuestion(question);
                    questionRevision.setTeacher(question.getTeacher());
                    questionRevision.setType(question.getType());
                    questionRevision.setQuestionText(question.getQuestionText());
                    questionRevision.setExplanation(question.getExplanation());
                    questionRevision.setCorrectAnswer(question.getCorrectAnswer());
                    questionRevision.setKnowledgePoint(question.getKnowledgePoint());
                    questionRevision.setOrderIndex(question.getOrderIndex());
                    questionRevision.setDifficulty(question.getDifficulty());
                    questionRevision.setXpReward(question.getXpReward());
                    lessonRevision.getQuestions().add(questionRevision);

                    for (QuestionOption option : question.getOptions()) {
                        QuestionOptionRevision optionRevision = new QuestionOptionRevision();
                        optionRevision.setQuestionRevision(questionRevision);
                        optionRevision.setSourceOption(option);
                        optionRevision.setOptionText(option.getOptionText());
                        optionRevision.setCorrect(option.isCorrect());
                        optionRevision.setOrderIndex(option.getOrderIndex());
                        questionRevision.getOptions().add(optionRevision);
                    }
                }
            }
        }
    }

    private void applyAudio(CourseImportPackage importPackage, LessonImportModel lessonModel, LessonRevision lesson,
            List<String> uploadedAudioPublicIds) {
        applyAudio(importPackage, lessonModel, lesson, uploadedAudioPublicIds, Map.of());
    }

    private void applyAudio(CourseImportPackage importPackage, LessonImportModel lessonModel, Lesson lesson,
            List<String> uploadedAudioPublicIds) {
        if (!hasText(lessonModel.audioFile())) {
            return;
        }

        CourseImportPackage.ImportFile audio = audioFile(importPackage, lessonModel.audioFile());
        audioFileValidator.validate(audio);
        clamAvClient.assertClean(audio.path(), audio.bytes());
        StorageUploadResult uploadResult = storageService.uploadLessonAudio(new ImportMultipartFile(audio));
        if (hasText(uploadResult.publicId())) {
            uploadedAudioPublicIds.add(uploadResult.publicId());
        }
        lesson.setAudioUrl(uploadResult.url());
        lesson.setAudioPublicId(uploadResult.publicId());
    }

    private void applyAudio(CourseImportPackage importPackage, LessonImportModel lessonModel, LessonRevision lesson,
            List<String> uploadedAudioPublicIds, Map<Long, LessonRevision> existingLessons) {
        if (!hasText(lessonModel.audioFile())) {
            if (lessonModel.lessonId() != null) {
                LessonRevision existingLesson = existingLessons.get(lessonModel.lessonId());
                if (existingLesson != null) {
                    lesson.setAudioUrl(existingLesson.getAudioUrl());
                    lesson.setAudioPublicId(existingLesson.getAudioPublicId());
                }
            }
            return;
        }

        CourseImportPackage.ImportFile audio = audioFile(importPackage, lessonModel.audioFile());
        audioFileValidator.validate(audio);
        clamAvClient.assertClean(audio.path(), audio.bytes());
        StorageUploadResult uploadResult = storageService.uploadLessonAudio(new ImportMultipartFile(audio));
        if (hasText(uploadResult.publicId())) {
            uploadedAudioPublicIds.add(uploadResult.publicId());
        }
        lesson.setAudioUrl(uploadResult.url());
        lesson.setAudioPublicId(uploadResult.publicId());
    }

    private void cleanupUploadedAudio(List<String> uploadedAudioPublicIds) {
        for (String publicId : uploadedAudioPublicIds) {
            try {
                storageService.delete(publicId, StorageResourceType.VIDEO);
            } catch (RuntimeException cleanupException) {
                log.warn("Failed to clean up uploaded import audio {}", publicId, cleanupException);
            }
        }
    }

    private CourseImportPackage.ImportFile audioFile(CourseImportPackage importPackage, String audioFile) {
        String path = audioFile.replace('\\', '/').replaceAll("^/+", "");
        CourseImportPackage.ImportFile file = importPackage.audioFiles().get(path);
        if (file == null && !path.startsWith("audio/")) {
            file = importPackage.audioFiles().get("audio/" + path);
        }
        if (file == null) {
            throw badRequest("Không tìm thấy file audio được tham chiếu.");
        }
        return file;
    }

    private void validateNewCourseModel(CourseImportModel model) {
        validateCourseModel(model);
    }

    private void validateAudioReferences(CourseImportPackage importPackage, CourseImportModel model) {
        Set<String> referenced = new HashSet<>();
        for (TopicImportModel topic : model.topics()) {
            for (LessonImportModel lesson : topic.lessons()) {
                if (!hasText(lesson.audioFile())) {
                    continue;
                }
                String path = lesson.audioFile().trim();
                if (!path.startsWith("audio/")) {
                    throw badRequest("File audio phải nằm trong thư mục audio/: " + path);
                }
                if (!importPackage.audioFiles().containsKey(path)) {
                    throw badRequest("Không tìm thấy file audio được tham chiếu: " + path);
                }
                referenced.add(path);
            }
        }
        for (String path : importPackage.audioFiles().keySet()) {
            if (!referenced.contains(path)) {
                throw badRequest("File audio không được tham chiếu trong Excel: " + path);
            }
        }
    }

    private void validateExistingCourseModel(CourseImportModel model, CourseRevision revision) {
        validateCourseModel(model);
        if (!revision.getCourse().getId().equals(model.courseId())) {
            throw badRequest("ID khóa học trong file không hợp lệ.");
        }
        Map<Long, TopicRevision> topics = topicsById(revision);
        Map<Long, LessonRevision> lessons = lessonsById(revision);
        Map<Long, QuestionRevision> questions = questionsById(revision);
        Map<Long, QuestionOptionRevision> options = optionsById(revision);
        Set<Long> importedTopicIds = new HashSet<>();
        Set<Long> importedLessonIds = new HashSet<>();
        Set<Long> importedQuestionIds = new HashSet<>();
        Set<Long> importedOptionIds = new HashSet<>();
        for (TopicImportModel topic : model.topics()) {
            TopicRevision existingTopic = null;
            if (topic.topicId() != null) {
                validateUniqueId(importedTopicIds, topic.topicId(), "ID chủ đề bị trùng trong file.");
                existingTopic = topics.get(topic.topicId());
            }
            if (topic.topicId() != null && existingTopic == null) {
                throw badRequest("ID chủ đề trong file không thuộc phiên bản khóa học hiện tại.");
            }
            for (LessonImportModel lesson : topic.lessons()) {
                LessonRevision existingLesson = null;
                if (lesson.lessonId() != null) {
                    validateUniqueId(importedLessonIds, lesson.lessonId(), "ID bài học bị trùng trong file.");
                    if (topic.topicId() == null) {
                        throw badRequest("ID bài học yêu cầu ID chủ đề trong file.");
                    }
                    existingLesson = lessons.get(lesson.lessonId());
                }
                if (lesson.lessonId() != null && existingLesson == null) {
                    throw badRequest("ID bài học trong file không thuộc phiên bản khóa học hiện tại.");
                }
                if (existingTopic != null && existingLesson != null
                        && !existingLesson.getTopicRevision().getId().equals(existingTopic.getId())) {
                    throw badRequest("ID bài học không thuộc chủ đề trong file.");
                }
                for (QuestionImportModel question : lesson.questions()) {
                    QuestionRevision existingQuestion = null;
                    if (question.questionId() != null) {
                        validateUniqueId(importedQuestionIds, question.questionId(), "ID câu hỏi bị trùng trong file.");
                        if (lesson.lessonId() == null) {
                            throw badRequest("ID câu hỏi yêu cầu ID bài học trong file.");
                        }
                        existingQuestion = questions.get(question.questionId());
                    }
                    if (question.questionId() != null && existingQuestion == null) {
                        throw badRequest("ID câu hỏi trong file không thuộc phiên bản khóa học hiện tại.");
                    }
                    if (existingLesson != null && existingQuestion != null
                            && !existingQuestion.getLessonRevision().getId().equals(existingLesson.getId())) {
                        throw badRequest("ID câu hỏi không thuộc bài học trong file.");
                    }
                    for (OptionImportModel option : question.options()) {
                        QuestionOptionRevision existingOption = null;
                        if (option.optionId() != null) {
                            validateUniqueId(importedOptionIds, option.optionId(), "ID đáp án bị trùng trong file.");
                            if (question.questionId() == null) {
                                throw badRequest("ID đáp án yêu cầu ID câu hỏi trong file.");
                            }
                            existingOption = options.get(option.optionId());
                        }
                        if (option.optionId() != null && existingOption == null) {
                            throw badRequest("ID đáp án trong file không thuộc phiên bản khóa học hiện tại.");
                        }
                        if (existingQuestion != null && existingOption != null
                                && !existingOption.getQuestionRevision().getId().equals(existingQuestion.getId())) {
                            throw badRequest("ID đáp án không thuộc câu hỏi trong file.");
                        }
                    }
                }
            }
        }
    }

    private void validateUniqueId(Set<Long> importedIds, Long id, String message) {
        if (!importedIds.add(id)) {
            throw badRequest(message);
        }
    }

    private void validateCourseModel(CourseImportModel model) {
        requireText(model.name(), "course_name");
        requireText(model.type(), "course_type");
        requireText(model.level(), "course_level");
        enumValue(CourseType.class, model.type(), "course_type");
        if (model.topics().isEmpty()) {
            throw badRequest("Khóa học phải có ít nhất một chủ đề.");
        }
        for (TopicImportModel topic : model.topics()) {
            requireText(topic.title(), "topic_title");
            if (topic.lessons().isEmpty()) {
                throw badRequest("Chủ đề phải có ít nhất một bài học.");
            }
            for (LessonImportModel lesson : topic.lessons()) {
                requireText(lesson.title(), "lesson_title");
                requireText(lesson.skillType(), "skill_type");
                enumValue(SkillType.class, lesson.skillType(), "skill_type");
                if (lesson.questions().isEmpty()) {
                    throw badRequest("Bài học phải có ít nhất một câu hỏi.");
                }
                for (QuestionImportModel question : lesson.questions()) {
                    requireText(question.type(), "question_type");
                    requireText(question.questionText(), "question_text");
                    QuestionType type = enumValue(QuestionType.class, question.type(), "question_type");
                    validateQuestion(type, question);
                }
            }
        }
    }

    private java.util.Optional<CourseRevision> latestEditableRevision(Course course) {
        return courseRevisionRepository.findTopByCourseAndStatusOrderByRevisionNumberDesc(course, CourseRevisionStatus.DRAFT)
                .or(() -> courseRevisionRepository.findTopByCourseAndStatusOrderByRevisionNumberDesc(
                        course, CourseRevisionStatus.REJECTED));
    }

    private java.util.Optional<CourseRevision> latestApprovedRevision(Course course) {
        return courseRevisionRepository.findTopByCourseAndStatusOrderByRevisionNumberDesc(
                course, CourseRevisionStatus.APPROVED);
    }

    private java.util.Optional<CourseRevision> latestUpdatableSourceRevision(Course course) {
        return courseRevisionRepository.findTopByCourseOrderByRevisionNumberDesc(course)
                .filter(revision -> revision.getStatus() == CourseRevisionStatus.DRAFT
                        || revision.getStatus() == CourseRevisionStatus.REJECTED
                        || revision.getStatus() == CourseRevisionStatus.PENDING_REVIEW
                        || revision.getStatus() == CourseRevisionStatus.APPROVED);
    }

    private void applyRevisionMetadata(CourseRevision revision, CourseImportModel model) {
        revision.setName(model.name().trim());
        revision.setDescription(model.description());
        revision.setType(enumValue(CourseType.class, model.type(), "course_type"));
        revision.setLevel(model.level().trim());
        revision.setOrderIndex(valueOrZero(model.orderIndex()));
    }

    private void applyRevisionPricingMetadata(CourseRevision revision, Course course) {
        revision.setPricingType(course.getPricingType());
        revision.setProposedPriceAmountVnd(course.getProposedPriceAmountVnd());
    }

    private void applyRevisionPricingMetadata(CourseRevision revision, CourseRevision sourceRevision) {
        CoursePricingType pricingType = sourceRevision.getPricingType() == null
                ? sourceRevision.getCourse().getPricingType()
                : sourceRevision.getPricingType();
        revision.setPricingType(pricingType);
        revision.setProposedPriceAmountVnd(sourceRevision.getProposedPriceAmountVnd());
    }

    private CourseRevision newDraftRevisionFrom(Course course, User teacher) {
        CourseRevision revision = new CourseRevision();
        revision.setCourse(course);
        revision.setTeacher(teacher);
        revision.setRevisionNumber(courseRevisionRepository.findTopByCourseOrderByRevisionNumberDesc(course)
                .map(latest -> latest.getRevisionNumber() + 1)
                .orElse(1));
        revision.setStatus(CourseRevisionStatus.DRAFT);
        return revision;
    }

    private Map<Long, TopicRevision> topicsById(CourseRevision revision) {
        Map<Long, TopicRevision> topics = new HashMap<>();
        for (TopicRevision topic : revision.getTopics()) {
            topics.put(topic.getId(), topic);
        }
        return topics;
    }

    private Map<Long, LessonRevision> lessonsById(CourseRevision revision) {
        Map<Long, LessonRevision> lessons = new HashMap<>();
        for (TopicRevision topic : revision.getTopics()) {
            for (LessonRevision lesson : topic.getLessons()) {
                lessons.put(lesson.getId(), lesson);
            }
        }
        return lessons;
    }

    private Map<Long, QuestionRevision> questionsById(CourseRevision revision) {
        Map<Long, QuestionRevision> questions = new HashMap<>();
        for (LessonRevision lesson : lessonsById(revision).values()) {
            for (QuestionRevision question : lesson.getQuestions()) {
                questions.put(question.getId(), question);
            }
        }
        return questions;
    }

    private Map<Long, QuestionOptionRevision> optionsById(CourseRevision revision) {
        Map<Long, QuestionOptionRevision> options = new HashMap<>();
        for (QuestionRevision question : questionsById(revision).values()) {
            for (QuestionOptionRevision option : question.getOptions()) {
                options.put(option.getId(), option);
            }
        }
        return options;
    }

    private void validateQuestion(QuestionType type, QuestionImportModel question) {
        if (type == QuestionType.MULTIPLE_CHOICE || type == QuestionType.TRUE_FALSE) {
            long correctCount = question.options().stream().filter(OptionImportModel::correct).count();
            if (question.options().size() < 2 || correctCount != 1) {
                throw badRequest("Câu hỏi trắc nghiệm phải có ít nhất hai lựa chọn và đúng một đáp án.");
            }
            return;
        }
        requireText(question.correctAnswer(), "correct_answer");
    }

    private <T extends Enum<T>> T enumValue(Class<T> enumType, String value, String field) {
        requireText(value, field);
        try {
            return Enum.valueOf(enumType, value.trim());
        } catch (IllegalArgumentException exception) {
            throw badRequest("Giá trị không hợp lệ cho cột " + field + ".");
        }
    }

    private void requireText(String value, String field) {
        if (!hasText(value)) {
            throw badRequest("Thiếu giá trị bắt buộc cho cột " + field + ".");
        }
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

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    private static final class ImportMultipartFile implements MultipartFile {
        private final CourseImportPackage.ImportFile file;

        private ImportMultipartFile(CourseImportPackage.ImportFile file) {
            this.file = file;
        }

        @Override
        public String getName() { return "file"; }

        @Override
        public String getOriginalFilename() { return file.path(); }

        @Override
        public String getContentType() { return file.contentType(); }

        @Override
        public boolean isEmpty() { return file.bytes().length == 0; }

        @Override
        public long getSize() { return file.bytes().length; }

        @Override
        public byte[] getBytes() { return file.bytes(); }

        @Override
        public InputStream getInputStream() { return new ByteArrayInputStream(file.bytes()); }

        @Override
        public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
            throw new UnsupportedOperationException("Import audio files are uploaded from memory.");
        }
    }
}
