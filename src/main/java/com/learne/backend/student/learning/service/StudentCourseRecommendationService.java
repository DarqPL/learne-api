package com.learne.backend.student.learning.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.monetization.enums.AiUsageFeature;
import com.learne.backend.monetization.service.AiUsageGuard;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.ai.AiCourseRecommendationRequest;
import com.learne.backend.student.learning.ai.AiCourseRecommendationResponse;
import com.learne.backend.student.learning.ai.AiLearningPathClient;
import com.learne.backend.student.learning.dto.CourseRecommendationItemResponse;
import com.learne.backend.student.learning.dto.CourseRecommendationPathResponse;
import com.learne.backend.student.learning.dto.GenerateCourseRecommendationsRequest;
import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.entity.CourseRecommendationItem;
import com.learne.backend.student.learning.entity.CourseRecommendationPath;
import com.learne.backend.student.learning.entity.PlacementAttempt;
import com.learne.backend.student.learning.entity.PlacementAttemptAnswer;
import com.learne.backend.student.learning.entity.PlacementAttemptQuestion;
import com.learne.backend.student.learning.enums.LearningPathSource;
import com.learne.backend.student.learning.enums.LearningPathStatus;
import com.learne.backend.student.learning.enums.PlacementAttemptStatus;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.CourseRecommendationItemRepository;
import com.learne.backend.student.learning.repository.CourseRecommendationPathRepository;
import com.learne.backend.student.learning.repository.PlacementAttemptRepository;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class StudentCourseRecommendationService {
    public static final int DEFAULT_MAX_ITEMS = 8;
    public static final int MAX_ITEMS = 12;

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final CourseRepository courseRepository;
    private final CourseEnrollmentRepository courseEnrollmentRepository;
    private final PlacementAttemptRepository placementAttemptRepository;
    private final CourseRecommendationPathRepository courseRecommendationPathRepository;
    private final CourseRecommendationItemRepository courseRecommendationItemRepository;
    private final AiLearningPathClient aiLearningPathClient;
    private final AiUsageGuard aiUsageGuard;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    public StudentCourseRecommendationService(
            CourseRepository courseRepository,
            CourseEnrollmentRepository courseEnrollmentRepository,
            PlacementAttemptRepository placementAttemptRepository,
            CourseRecommendationPathRepository courseRecommendationPathRepository,
            CourseRecommendationItemRepository courseRecommendationItemRepository,
            AiLearningPathClient aiLearningPathClient,
            AiUsageGuard aiUsageGuard,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.courseRepository = courseRepository;
        this.courseEnrollmentRepository = courseEnrollmentRepository;
        this.placementAttemptRepository = placementAttemptRepository;
        this.courseRecommendationPathRepository = courseRecommendationPathRepository;
        this.courseRecommendationItemRepository = courseRecommendationItemRepository;
        this.aiLearningPathClient = aiLearningPathClient;
        this.aiUsageGuard = aiUsageGuard;
        this.objectMapper = objectMapper;
        this.transactionManager = transactionManager;
    }

    public CourseRecommendationPathResponse generate(User student, GenerateCourseRecommendationsRequest request) {
        requireStudent(student);
        int maxItems = boundedMaxItems(request);
        GenerateContext context = readOnlyTransaction(() -> generateContext(student, maxItems));

        aiUsageGuard.recordAcceptedUsage(student, AiUsageFeature.COURSE_RECOMMENDATION);
        AiCourseRecommendationResponse aiResponse = aiLearningPathClient.generateCourseRecommendations(context.aiRequest());

        return writeTransaction(() -> saveGeneratedPath(student, context.candidates(), context.enrollmentsByCourseId(), maxItems, aiResponse));
    }

    @Transactional(readOnly = true)
    public CourseRecommendationPathResponse current(User student) {
        requireStudent(student);
        CourseRecommendationPath path = courseRecommendationPathRepository
                .findFirstByUserAndStatusOrderByGeneratedAtDesc(student, LearningPathStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course recommendation path not found."));
        Map<Long, CourseEnrollment> enrollments = enrollmentsByCourseId(student);
        return toResponse(path, courseRecommendationItemRepository.findByPathOrderBySequenceOrderAsc(path), enrollments);
    }

    private GenerateContext generateContext(User student, int maxItems) {
        PlacementAttempt placementAttempt = latestSubmittedPlacement(student);
        Map<Long, CourseEnrollment> enrollments = enrollmentsByCourseId(student);
        Map<Long, Course> candidates = courseRepository.findApprovedCourseRecommendationCandidates(ContentStatus.APPROVED).stream()
                .filter(this::hasApprovedNonPlacementLesson)
                .collect(Collectors.toMap(Course::getId, Function.identity(), (first, ignored) -> first, LinkedHashMap::new));
        if (candidates.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No approved courses are available for recommendation.");
        }

        List<String> weaknesses = placementWeaknesses(placementAttempt);
        AiCourseRecommendationRequest aiRequest = new AiCourseRecommendationRequest(
                new AiCourseRecommendationRequest.StudentPayload(student.getId(), levelForScore(placementAttempt.getScorePercent())),
                new AiCourseRecommendationRequest.AssessmentSummaryPayload(placementAttempt.getId(), levelForScore(placementAttempt.getScorePercent()),
                        placementAttempt.getScorePercent(), placementAttempt.getSubmittedAt() == null ? null : placementAttempt.getSubmittedAt().toString()),
                skillStats(placementAttempt),
                new AiCourseRecommendationRequest.LearningHistoryPayload(
                        enrollments.values().stream().map(enrollment -> enrollment.getCourse().getId()).toList(),
                        List.of(),
                        weaknesses),
                candidates.values().stream().map(course -> toCandidatePayload(course, enrollments.containsKey(course.getId()))).toList(),
                new AiCourseRecommendationRequest.ConstraintPayload(maxItems, new ArrayList<>(candidates.keySet())));
        return new GenerateContext(candidates, enrollments, aiRequest);
    }

    private PlacementAttempt latestSubmittedPlacement(User student) {
        return placementAttemptRepository.findByUserAndStatus(student, PlacementAttemptStatus.SUBMITTED).stream()
                .max(Comparator.comparing(PlacementAttempt::getSubmittedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PlacementAttempt::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Placement test is required before generating course recommendations."));
    }

    private CourseRecommendationPathResponse saveGeneratedPath(User student, Map<Long, Course> candidates,
            Map<Long, CourseEnrollment> enrollmentsByCourseId, int maxItems, AiCourseRecommendationResponse aiResponse) {
        List<CourseRecommendationItem> items = validRecommendations(aiResponse, candidates, maxItems);
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI service returned no valid course recommendations.");
        }

        courseRecommendationPathRepository.findByUserAndStatusForUpdate(student, LearningPathStatus.ACTIVE)
                .forEach(path -> path.setStatus(LearningPathStatus.ARCHIVED));
        courseRecommendationPathRepository.flush();

        CourseRecommendationPath path = new CourseRecommendationPath();
        path.setUser(student);
        path.setStatus(LearningPathStatus.ACTIVE);
        path.setSource(LearningPathSource.AI_SERVICE);
        path.setGeneratedAt(Instant.now());
        path.setWeaknessesJson(serializeWeaknesses(aiResponse == null ? null : aiResponse.weaknesses()));
        CourseRecommendationPath savedPath = courseRecommendationPathRepository.save(path);

        for (CourseRecommendationItem item : items) {
            item.setPath(savedPath);
            savedPath.getItems().add(item);
        }
        courseRecommendationItemRepository.saveAll(items);

        return toResponse(savedPath, items, enrollmentsByCourseId);
    }

    private List<CourseRecommendationItem> validRecommendations(AiCourseRecommendationResponse aiResponse, Map<Long, Course> candidates, int maxItems) {
        if (aiResponse == null || aiResponse.recommendations() == null) {
            return List.of();
        }
        List<CourseRecommendationItem> items = new ArrayList<>();
        Set<Long> seenCourseIds = new HashSet<>();
        for (AiCourseRecommendationResponse.RecommendationPayload recommendation : aiResponse.recommendations()) {
            if (items.size() >= maxItems || recommendation == null || recommendation.courseId() == null) {
                continue;
            }
            Course course = candidates.get(recommendation.courseId());
            if (course == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "AI service returned an invalid course id.");
            }
            BigDecimal score = recommendation.score();
            if (score == null || score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
                continue;
            }
            if (!seenCourseIds.add(recommendation.courseId())) {
                continue;
            }
            CourseRecommendationItem item = new CourseRecommendationItem();
            item.setCourse(course);
            item.setSequenceOrder(items.size() + 1);
            item.setReason(blankToDefaultReason(recommendation.reason()));
            item.setScore(score);
            items.add(item);
        }
        return items;
    }

    private CourseRecommendationPathResponse toResponse(CourseRecommendationPath path, List<CourseRecommendationItem> items,
            Map<Long, CourseEnrollment> enrollmentsByCourseId) {
        return new CourseRecommendationPathResponse(
                path.getId(),
                path.getStatus().name(),
                path.getGeneratedAt(),
                path.getSource().name(),
                parseWeaknesses(path.getWeaknessesJson()),
                items.stream().map(item -> toItemResponse(item, enrollmentsByCourseId.get(item.getCourse().getId()))).toList());
    }

    private CourseRecommendationItemResponse toItemResponse(CourseRecommendationItem item, CourseEnrollment enrollment) {
        Course course = item.getCourse();
        User teacher = course.getTeacher();
        return new CourseRecommendationItemResponse(
                item.getId(),
                course.getId(),
                course.getName(),
                course.getDescription(),
                course.getType() == null ? null : course.getType().name(),
                course.getLevel(),
                teacher == null ? null : teacher.getId(),
                teacher == null ? null : teacher.getFullName(),
                enrollment != null,
                enrollment == null || enrollment.getStatus() == null ? null : enrollment.getStatus().name(),
                item.getSequenceOrder(),
                item.getReason(),
                item.getScore());
    }

    private Map<Long, CourseEnrollment> enrollmentsByCourseId(User student) {
        return courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student).stream()
                .collect(Collectors.toMap(
                        enrollment -> enrollment.getCourse().getId(),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private boolean hasApprovedNonPlacementLesson(Course course) {
        return course.getTopics().stream()
                .flatMap(topic -> topic.getLessons().stream())
                .anyMatch(lesson -> lesson.getStatus() == ContentStatus.APPROVED && !lesson.isPlacementTest());
    }

    private AiCourseRecommendationRequest.CandidateCoursePayload toCandidatePayload(Course course, boolean enrolled) {
        return new AiCourseRecommendationRequest.CandidateCoursePayload(
                course.getId(),
                course.getName(),
                course.getName(),
                course.getType() == null ? null : course.getType().name(),
                course.getLevel(),
                course.getDescription(),
                course.getTeacher() == null ? null : course.getTeacher().getFullName(),
                enrolled);
    }

    private List<AiCourseRecommendationRequest.SkillStatPayload> skillStats(PlacementAttempt attempt) {
        Map<Long, PlacementAttemptAnswer> answersByQuestionId = attempt.getAnswers().stream()
                .collect(Collectors.toMap(answer -> answer.getQuestion().getId(), Function.identity(), (first, ignored) -> first));
        Map<String, SkillStat> stats = new LinkedHashMap<>();
        for (PlacementAttemptQuestion attemptQuestion : attempt.getQuestions()) {
            PlacementAttemptAnswer answer = answersByQuestionId.get(attemptQuestion.getQuestion().getId());
            SkillStat stat = stats.computeIfAbsent(attemptQuestion.getSkill(), ignored -> new SkillStat());
            stat.attempts++;
            stat.totalScore += answer == null ? 0 : answer.getScorePercent();
            Question question = attemptQuestion.getQuestion();
            if (answer != null && !answer.isCorrect()
                    && question.getKnowledgePoint() != null && !question.getKnowledgePoint().isBlank()) {
                stat.weakKnowledgePoints.add(question.getKnowledgePoint().trim());
            }
        }
        return stats.entrySet().stream()
                .map(entry -> new AiCourseRecommendationRequest.SkillStatPayload(
                        entry.getKey(),
                        entry.getValue().attempts == 0 ? 0 : Math.round((float) entry.getValue().totalScore / entry.getValue().attempts),
                        entry.getValue().attempts,
                        List.copyOf(entry.getValue().weakKnowledgePoints)))
                .toList();
    }

    private List<String> placementWeaknesses(PlacementAttempt attempt) {
        Map<Long, PlacementAttemptQuestion> attemptQuestionsByQuestionId = attempt.getQuestions().stream()
                .collect(Collectors.toMap(item -> item.getQuestion().getId(), Function.identity(), (first, ignored) -> first));
        List<String> weaknesses = new ArrayList<>();
        for (PlacementAttemptAnswer answer : attempt.getAnswers()) {
            Question question = answer.getQuestion();
            if (answer.isCorrect()) {
                continue;
            }
            PlacementAttemptQuestion attemptQuestion = attemptQuestionsByQuestionId.get(question.getId());
            if (attemptQuestion != null && attemptQuestion.getSkill() != null && !attemptQuestion.getSkill().isBlank()) {
                weaknesses.add(attemptQuestion.getSkill());
            }
            if (question.getKnowledgePoint() != null && !question.getKnowledgePoint().isBlank()) {
                weaknesses.add(question.getKnowledgePoint().trim());
            }
        }
        return weaknesses.stream().distinct().toList();
    }

    private String levelForScore(int scorePercent) {
        if (scorePercent >= 80) {
            return "B1";
        }
        if (scorePercent >= 60) {
            return "A2";
        }
        return "A1";
    }

    private int boundedMaxItems(GenerateCourseRecommendationsRequest request) {
        Integer requested = request == null ? null : request.maxItems();
        if (requested == null || requested <= 0) {
            return DEFAULT_MAX_ITEMS;
        }
        return Math.min(requested, MAX_ITEMS);
    }

    private void requireStudent(User user) {
        if (user == null || user.getRole() != UserRole.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Student access is required.");
        }
    }

    private String blankToDefaultReason(String reason) {
        return reason == null || reason.isBlank()
                ? "Recommended based on placement test results."
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
        if (transactionManager == null) {
            return supplier.get();
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template.execute(status -> supplier.get());
    }

    private <T> T writeTransaction(TransactionCallbackSupplier<T> supplier) {
        if (transactionManager == null) {
            return supplier.get();
        }
        return new TransactionTemplate(transactionManager).execute(status -> supplier.get());
    }

    @FunctionalInterface
    private interface TransactionCallbackSupplier<T> {
        T get();
    }

    private record GenerateContext(Map<Long, Course> candidates, Map<Long, CourseEnrollment> enrollmentsByCourseId,
            AiCourseRecommendationRequest aiRequest) {}

    private static class SkillStat {
        private int attempts;
        private int totalScore;
        private Set<String> weakKnowledgePoints = new java.util.LinkedHashSet<>();
    }
}
