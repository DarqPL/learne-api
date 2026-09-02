package com.learne.backend.student.learning.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.ai.AiLearningPathClient;
import com.learne.backend.student.learning.ai.AiLearningPathRequest;
import com.learne.backend.student.learning.ai.AiLearningPathResponse;
import com.learne.backend.student.learning.dto.GenerateLearningPathRequest;
import com.learne.backend.student.learning.dto.NextLessonRecommendationResponse;
import com.learne.backend.student.learning.dto.StudentLearningPathItemResponse;
import com.learne.backend.student.learning.dto.StudentLearningPathResponse;
import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.entity.LearningPath;
import com.learne.backend.student.learning.entity.LearningPathItem;
import com.learne.backend.student.learning.entity.LessonProgress;
import com.learne.backend.student.learning.entity.QuestionAnswer;
import com.learne.backend.student.learning.entity.QuizAttempt;
import com.learne.backend.student.learning.enums.EnrollmentStatus;
import com.learne.backend.student.learning.enums.LearningPathStatus;
import com.learne.backend.student.learning.enums.LearningPathSource;
import com.learne.backend.student.learning.enums.LessonProgressStatus;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.LearningPathItemRepository;
import com.learne.backend.student.learning.repository.LearningPathRepository;
import com.learne.backend.student.learning.repository.QuestionAnswerRepository;
import com.learne.backend.student.learning.repository.QuizAttemptRepository;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.enums.ContentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class StudentLearningPathService {
    public static final int DEFAULT_MAX_ITEMS = 8;
    public static final int MAX_ITEMS = 12;

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final CourseEnrollmentRepository courseEnrollmentRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final QuestionAnswerRepository questionAnswerRepository;
    private final LearningPathRepository learningPathRepository;
    private final LearningPathItemRepository learningPathItemRepository;
    private final AiLearningPathClient aiLearningPathClient;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    public StudentLearningPathService(
            CourseEnrollmentRepository courseEnrollmentRepository,
            QuizAttemptRepository quizAttemptRepository,
            QuestionAnswerRepository questionAnswerRepository,
            LearningPathRepository learningPathRepository,
            LearningPathItemRepository learningPathItemRepository,
            AiLearningPathClient aiLearningPathClient,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.courseEnrollmentRepository = courseEnrollmentRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.questionAnswerRepository = questionAnswerRepository;
        this.learningPathRepository = learningPathRepository;
        this.learningPathItemRepository = learningPathItemRepository;
        this.aiLearningPathClient = aiLearningPathClient;
        this.objectMapper = objectMapper;
        this.transactionManager = transactionManager;
    }

    public StudentLearningPathResponse generate(User student, GenerateLearningPathRequest request) {
        int maxItems = Math.min(request == null || request.maxItems() == null ? DEFAULT_MAX_ITEMS : request.maxItems(), MAX_ITEMS);
        Long teacherId = request == null ? null : request.teacherId();
        GenerateContext context = readOnlyTransaction(() -> generateContext(student, teacherId, maxItems));

        AiLearningPathResponse aiResponse = aiLearningPathClient.generateLearningPath(context.aiRequest());

        return writeTransaction(() -> saveGeneratedPath(student, context.candidates(), context.activeEnrollmentsByCourseId(), maxItems, aiResponse));
    }

    private GenerateContext generateContext(User student, Long teacherId, int maxItems) {
        CandidateContext candidateContext = candidateLessons(student, teacherId);
        if (candidateContext.lessonsById().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No approved enrolled lessons are available for recommendation.");
        }

        AiLearningPathRequest aiRequest = new AiLearningPathRequest(
                new AiLearningPathRequest.StudentPayload(student.getId(), null),
                new AiLearningPathRequest.ConstraintPayload(maxItems, new ArrayList<>(candidateContext.lessonsById().keySet())),
                new AiLearningPathRequest.LearningHistoryPayload(
                        recentQuizAttempts(student),
                        recentQuestionAnswers(student)),
                candidateContext.lessonsById().values().stream().map(this::toCandidatePayload).toList());
        return new GenerateContext(candidateContext.lessonsById(), candidateContext.activeEnrollmentsByCourseId(), aiRequest);
    }

    private StudentLearningPathResponse saveGeneratedPath(User student, Map<Long, Lesson> candidates,
            Map<Long, CourseEnrollment> activeEnrollmentsByCourseId, int maxItems,
            AiLearningPathResponse aiResponse) {
        List<LearningPathItem> items = validRecommendations(aiResponse, candidates, maxItems);
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI service returned no valid recommendations.");
        }

        learningPathRepository.findByUserAndStatus(student, LearningPathStatus.ACTIVE)
                .forEach(path -> path.setStatus(LearningPathStatus.ARCHIVED));

        LearningPath path = new LearningPath();
        path.setUser(student);
        path.setStatus(LearningPathStatus.ACTIVE);
        path.setSource(LearningPathSource.AI_SERVICE);
        path.setGeneratedAt(Instant.now());
        path.setWeaknessesJson(serializeWeaknesses(aiResponse == null ? null : aiResponse.weaknesses()));
        LearningPath savedPath = learningPathRepository.save(path);

        for (LearningPathItem item : items) {
            item.setPath(savedPath);
            savedPath.getItems().add(item);
        }
        learningPathItemRepository.saveAll(items);

        return toResponse(savedPath, items, Set.of(), activeEnrollmentsByCourseId);
    }

    @Transactional(readOnly = true)
    public StudentLearningPathResponse current(User student) {
        LearningPath path = findActivePath(student);
        Map<Long, CourseEnrollment> enrollments = activeEnrollmentsByCourseId(student);
        Set<Long> completedLessonIds = enrollments.values().stream()
                .flatMap(enrollment -> completedLessonIds(enrollment).stream())
                .collect(java.util.stream.Collectors.toSet());
        return toResponse(path, learningPathItemRepository.findByPathOrderBySequenceOrderAsc(path), completedLessonIds, enrollments);
    }

    @Transactional(readOnly = true)
    public NextLessonRecommendationResponse nextLesson(User student) {
        LearningPath path = findActivePath(student);
        EligibilityContext context = eligibilityContext(student);
        return learningPathItemRepository.findByPathOrderBySequenceOrderAsc(path).stream()
                .filter(item -> isEligibleNextLesson(item, context))
                .findFirst()
                .map(item -> toNextLessonResponse(item, context.activeEnrollmentsByCourseId().get(item.getLesson().getTopic().getCourse().getId())))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No recommendation available."));
    }

    private boolean isEligibleNextLesson(LearningPathItem item, EligibilityContext context) {
        Lesson lesson = item.getLesson();
        if (isCompleted(item, context.completedLessonIds(), lesson)) {
            return false;
        }
        Course course = lesson.getTopic().getCourse();
        if (course.getStatus() != ContentStatus.APPROVED || lesson.getStatus() != ContentStatus.APPROVED) {
            return false;
        }
        CourseEnrollment enrollment = context.activeEnrollmentsByCourseId().get(course.getId());
        return enrollment != null && isActionable(lessonProgressStatus(enrollment, lesson));
    }

    private LearningPath findActivePath(User student) {
        return learningPathRepository.findFirstByUserAndStatusOrderByGeneratedAtDesc(student, LearningPathStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Learning path not found."));
    }

    private StudentLearningPathResponse toResponse(LearningPath path, List<LearningPathItem> items, Set<Long> completedLessonIds,
            Map<Long, CourseEnrollment> activeEnrollmentsByCourseId) {
        return new StudentLearningPathResponse(
                path.getId(),
                path.getStatus().name(),
                path.getGeneratedAt(),
                path.getSource().name(),
                parseWeaknesses(path.getWeaknessesJson()),
                items.stream().map(item -> toItemResponse(item, completedLessonIds, activeEnrollmentsByCourseId)).toList());
    }

    private StudentLearningPathItemResponse toItemResponse(LearningPathItem item, Set<Long> completedLessonIds,
            Map<Long, CourseEnrollment> activeEnrollmentsByCourseId) {
        Lesson lesson = item.getLesson();
        Course course = lesson.getTopic().getCourse();
        User teacher = course.getTeacher();
        CourseEnrollment enrollment = activeEnrollmentsByCourseId.get(course.getId());
        return new StudentLearningPathItemResponse(
                item.getId(),
                enrollment == null ? null : enrollment.getId(),
                lesson.getId(),
                lesson.getTitle(),
                course.getId(),
                course.getName(),
                teacher.getId(),
                teacher.getFullName(),
                item.getSequenceOrder(),
                item.getReason(),
                item.getScore(),
                isCompleted(item, completedLessonIds, lesson));
    }

    private boolean isCompleted(LearningPathItem item, Set<Long> completedLessonIds, Lesson lesson) {
        return item.isCompleted() || completedLessonIds.contains(lesson.getId());
    }

    private EligibilityContext eligibilityContext(User student) {
        Map<Long, CourseEnrollment> enrollments = activeEnrollmentsByCourseId(student);
        Set<Long> completedLessonIds = enrollments.values().stream()
                .flatMap(enrollment -> completedLessonIds(enrollment).stream())
                .collect(java.util.stream.Collectors.toSet());
        return new EligibilityContext(enrollments, completedLessonIds);
    }

    private Set<Long> completedLessonIds(User student) {
        return courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student).stream()
                .flatMap(enrollment -> completedLessonIds(enrollment).stream())
                .collect(java.util.stream.Collectors.toSet());
    }

    private Map<Long, CourseEnrollment> activeEnrollmentsByCourseId(User student) {
        return courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student).stream()
                .filter(enrollment -> enrollment.getStatus() == EnrollmentStatus.ACTIVE)
                .collect(java.util.stream.Collectors.toMap(
                        enrollment -> enrollment.getCourse().getId(),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private NextLessonRecommendationResponse toNextLessonResponse(LearningPathItem item, CourseEnrollment enrollment) {
        Lesson lesson = item.getLesson();
        Course course = lesson.getTopic().getCourse();
        User teacher = course.getTeacher();
        return new NextLessonRecommendationResponse(
                enrollment.getId(),
                lesson.getId(),
                lesson.getTitle(),
                course.getId(),
                course.getName(),
                teacher.getId(),
                teacher.getFullName(),
                item.getReason(),
                item.getScore());
    }

    private CandidateContext candidateLessons(User student, Long teacherId) {
        Map<Long, Lesson> candidates = new LinkedHashMap<>();
        Map<Long, CourseEnrollment> activeEnrollmentsByCourseId = new LinkedHashMap<>();
        for (CourseEnrollment enrollment : courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student)) {
            Course course = enrollment.getCourse();
            if (enrollment.getStatus() != EnrollmentStatus.ACTIVE || course.getStatus() != ContentStatus.APPROVED) {
                continue;
            }
            if (teacherId != null && !teacherId.equals(course.getTeacher().getId())) {
                continue;
            }
            activeEnrollmentsByCourseId.putIfAbsent(course.getId(), enrollment);
            enrollment.getLessonProgress().stream()
                    .sorted(Comparator
                            .comparingInt((LessonProgress progress) -> progress.getLesson().getTopic().getOrderIndex())
                            .thenComparing(progress -> progress.getLesson().getTopic().getId())
                            .thenComparingInt(progress -> progress.getLesson().getOrderIndex())
                            .thenComparing(progress -> progress.getLesson().getId()))
                    .filter(progress -> isActionable(progress.getStatus()))
                    .map(LessonProgress::getLesson)
                    .filter(lesson -> lesson.getStatus() == ContentStatus.APPROVED)
                    .forEach(lesson -> candidates.putIfAbsent(lesson.getId(), lesson));
        }
        return new CandidateContext(candidates, activeEnrollmentsByCourseId);
    }

    private boolean isActionable(LessonProgressStatus status) {
        return status == LessonProgressStatus.UNLOCKED || status == LessonProgressStatus.IN_PROGRESS;
    }

    private LessonProgressStatus lessonProgressStatus(CourseEnrollment enrollment, Lesson lesson) {
        return enrollment.getLessonProgress().stream()
                .filter(progress -> progress.getLesson().getId().equals(lesson.getId()))
                .map(LessonProgress::getStatus)
                .findFirst()
                .orElse(LessonProgressStatus.LOCKED);
    }

    private Set<Long> completedLessonIds(CourseEnrollment enrollment) {
        Set<Long> lessonIds = new HashSet<>();
        for (LessonProgress progress : enrollment.getLessonProgress()) {
            if (progress.getStatus() == LessonProgressStatus.COMPLETED) {
                lessonIds.add(progress.getLesson().getId());
            }
        }
        return lessonIds;
    }

    private List<AiLearningPathRequest.QuizAttemptPayload> recentQuizAttempts(User student) {
        return quizAttemptRepository.findRecentByUser(student, PageRequest.of(0, 5)).stream()
                .map(attempt -> new AiLearningPathRequest.QuizAttemptPayload(
                        attempt.getLesson().getId(),
                        attempt.getLesson().getTitle(),
                        attempt.getScorePercent(),
                        attempt.isPassed(),
                        attempt.getSubmittedAt() == null ? null : attempt.getSubmittedAt().toString()))
                .toList();
    }

    private List<AiLearningPathRequest.QuestionAnswerPayload> recentQuestionAnswers(User student) {
        return questionAnswerRepository.findRecentByUser(student, PageRequest.of(0, 50)).stream()
                .map(answer -> {
                    Question question = answer.getQuestion();
                    QuizAttempt attempt = answer.getQuizAttempt();
                    return new AiLearningPathRequest.QuestionAnswerPayload(
                            question.getId(),
                            attempt.getLesson().getId(),
                            question.getType().name(),
                            question.getQuestionText(),
                            answer.getAnswerText(),
                            answer.isCorrect(),
                            answer.getScorePercent(),
                            answer.getFeedback());
                })
                .toList();
    }

    private AiLearningPathRequest.CandidateLessonPayload toCandidatePayload(Lesson lesson) {
        Course course = lesson.getTopic().getCourse();
        return new AiLearningPathRequest.CandidateLessonPayload(
                lesson.getId(),
                lesson.getTitle(),
                course.getId(),
                course.getName(),
                course.getTeacher().getId(),
                course.getLevel(),
                lesson.getTopic().getName(),
                lesson.getDescription());
    }

    private List<LearningPathItem> validRecommendations(AiLearningPathResponse aiResponse, Map<Long, Lesson> candidates, int maxItems) {
        if (aiResponse == null || aiResponse.recommendations() == null) {
            return List.of();
        }
        List<LearningPathItem> items = new ArrayList<>();
        Set<Long> seenLessonIds = new HashSet<>();
        for (AiLearningPathResponse.RecommendationPayload recommendation : aiResponse.recommendations()) {
            if (items.size() >= maxItems || recommendation == null || recommendation.lessonId() == null) {
                continue;
            }
            Lesson lesson = candidates.get(recommendation.lessonId());
            BigDecimal score = recommendation.score();
            if (lesson == null || score == null || score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
                continue;
            }
            if (!seenLessonIds.add(recommendation.lessonId())) {
                continue;
            }
            LearningPathItem item = new LearningPathItem();
            item.setLesson(lesson);
            item.setSequenceOrder(items.size() + 1);
            item.setReason(blankToDefaultReason(recommendation.reason()));
            item.setScore(score);
            item.setCompleted(false);
            items.add(item);
        }
        return items;
    }

    private String blankToDefaultReason(String reason) {
        return reason == null || reason.isBlank()
                ? "Recommended based on recent learning history."
                : reason;
    }

    private String serializeWeaknesses(List<String> weaknesses) {
        try {
            return objectMapper.writeValueAsString(weaknesses == null ? List.of() : weaknesses);
        } catch (JacksonException ex) {
            return "[]";
        }
    }

    private List<String> parseWeaknesses(String weaknessesJson) {
        if (weaknessesJson == null || weaknessesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> weaknesses = objectMapper.readValue(weaknessesJson, STRING_LIST_TYPE);
            return weaknesses == null ? List.of() : weaknesses;
        } catch (JacksonException ex) {
            return List.of();
        }
    }

    private <T> T readOnlyTransaction(TransactionCallbackSupplier<T> supplier) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template.execute(status -> supplier.get());
    }

    private <T> T writeTransaction(TransactionCallbackSupplier<T> supplier) {
        return new TransactionTemplate(transactionManager).execute(status -> supplier.get());
    }

    @FunctionalInterface
    private interface TransactionCallbackSupplier<T> {
        T get();
    }

    private record GenerateContext(Map<Long, Lesson> candidates, Map<Long, CourseEnrollment> activeEnrollmentsByCourseId,
            AiLearningPathRequest aiRequest) {
    }

    private record CandidateContext(Map<Long, Lesson> lessonsById, Map<Long, CourseEnrollment> activeEnrollmentsByCourseId) {
    }

    private record EligibilityContext(Map<Long, CourseEnrollment> activeEnrollmentsByCourseId, Set<Long> completedLessonIds) {
    }
}
