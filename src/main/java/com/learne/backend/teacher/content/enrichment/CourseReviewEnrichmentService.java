package com.learne.backend.teacher.content.enrichment;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.QuestionOptionRevision;
import com.learne.backend.teacher.content.entity.QuestionRevision;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.enums.SkillType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CourseReviewEnrichmentService {
    static final int MAX_KNOWLEDGE_POINT_LENGTH = 64;
    static final int MAX_QUESTIONS_PER_COURSE = 200;
    static final int MAX_QUESTIONS_PER_CHUNK = 25;
    static final String ENRICHMENT_FAILURE_MESSAGE =
            "Không thể tự động gắn điểm kiến thức. Vui lòng thử gửi duyệt lại sau.";
    static final String TOO_LARGE_MESSAGE =
            "Khóa học có quá nhiều câu hỏi để tự động gắn điểm kiến thức. Vui lòng giảm số câu hỏi hoặc chia nhỏ khóa học.";
    static final String CHANGED_MESSAGE =
            "Nội dung khóa học đã thay đổi trong khi gửi duyệt. Vui lòng thử gửi duyệt lại.";

    private final CourseKnowledgePointClient knowledgePointClient;
    private final QuestionDifficultyCalculator difficultyCalculator;
    private final QuestionXpPolicy xpPolicy;

    public CourseReviewEnrichmentService(
            CourseKnowledgePointClient knowledgePointClient,
            QuestionDifficultyCalculator difficultyCalculator,
            QuestionXpPolicy xpPolicy) {
        this.knowledgePointClient = knowledgePointClient;
        this.difficultyCalculator = difficultyCalculator;
        this.xpPolicy = xpPolicy;
    }

    public static String normalizeKnowledgePoint(String value) {
        if (value == null) {
            throw enrichmentFailure();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s-]+", "_")
                .replaceAll("[^a-z0-9_]", "")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isBlank() || normalized.length() > MAX_KNOWLEDGE_POINT_LENGTH) {
            throw enrichmentFailure();
        }
        return normalized;
    }

    public CourseRevisionSnapshot buildSnapshot(CourseRevision revision) {
        List<QuestionSnapshot> questions = new ArrayList<>();
        for (TopicRevision topic : sortedTopics(revision)) {
            for (LessonRevision lesson : sortedLessons(topic)) {
                for (QuestionRevision question : sortedQuestions(lesson)) {
                    CourseKnowledgePointRequest.QuestionPayload payload = toPayload(revision, topic, lesson, question);
                    String difficulty = difficultyCalculator.calculate(
                            revision.getLevel(),
                            lesson.getSkillType(),
                            lesson.getContent(),
                            question.getType(),
                            question.getQuestionText(),
                            question.getCorrectAnswer(),
                            sortedOptions(question).stream().map(QuestionOptionRevision::getOptionText).toList());
                    questions.add(new QuestionSnapshot(
                            question.getId(),
                            difficulty,
                            xpPolicy.xpFor(difficulty),
                            payload,
                            question));
                }
            }
        }
        if (questions.size() > MAX_QUESTIONS_PER_COURSE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, TOO_LARGE_MESSAGE);
        }
        CourseKnowledgePointRequest.CoursePayload course = new CourseKnowledgePointRequest.CoursePayload(
                revision.getCourse() == null ? null : revision.getCourse().getId(),
                bounded(revision.getName(), 255),
                bounded(revision.getDescription(), 1000),
                revision.getType() == null ? "" : revision.getType().name(),
                bounded(revision.getLevel(), 32));
        return new CourseRevisionSnapshot(
                revision.getId(),
                revision.getRevisionNumber(),
                fingerprint(revision),
                course,
                questions);
    }

    public EnrichedCourseRevisionSnapshot enrichSnapshot(CourseRevisionSnapshot snapshot) {
        if (snapshot.questions().isEmpty()) {
            return new EnrichedCourseRevisionSnapshot(snapshot, Map.of());
        }

        Map<Long, String> knowledgePoints = new HashMap<>();
        for (int start = 0; start < snapshot.questions().size(); start += MAX_QUESTIONS_PER_CHUNK) {
            List<QuestionSnapshot> chunk = snapshot.questions().subList(
                    start, Math.min(start + MAX_QUESTIONS_PER_CHUNK, snapshot.questions().size()));
            CourseKnowledgePointResponse response = knowledgePointClient.enrichKnowledgePoints(
                    new CourseKnowledgePointRequest(
                            snapshot.course(),
                            chunk.stream().map(QuestionSnapshot::payload).toList()));
            knowledgePoints.putAll(validateResponse(
                    response,
                    chunk.stream().map(QuestionSnapshot::questionId).collect(Collectors.toSet())));
        }
        return new EnrichedCourseRevisionSnapshot(snapshot, knowledgePoints);
    }

    public void enrichSnapshot(CourseRevisionSnapshot snapshot, CourseRevision currentRevision) {
        EnrichedCourseRevisionSnapshot enrichedSnapshot = enrichSnapshot(snapshot);
        applyEnrichment(enrichedSnapshot, currentRevision);
    }

    public void applyEnrichment(EnrichedCourseRevisionSnapshot enrichedSnapshot, CourseRevision currentRevision) {
        CourseRevisionSnapshot snapshot = enrichedSnapshot.snapshot();
        if (!snapshot.fingerprint().equals(fingerprint(currentRevision))) {
            throw new ApiException(HttpStatus.CONFLICT, CHANGED_MESSAGE);
        }
        Map<Long, QuestionRevision> currentQuestions = currentRevision.getTopics().stream()
                .flatMap(topic -> topic.getLessons().stream())
                .flatMap(lesson -> lesson.getQuestions().stream())
                .filter(question -> question.getId() != null)
                .collect(Collectors.toMap(QuestionRevision::getId, question -> question));
        for (QuestionSnapshot question : snapshot.questions()) {
            QuestionRevision currentQuestion = currentQuestions.get(question.questionId());
            if (currentQuestion == null) {
                throw new ApiException(HttpStatus.CONFLICT, CHANGED_MESSAGE);
            }
            currentQuestion.setKnowledgePoint(enrichedSnapshot.knowledgePoints().get(question.questionId()));
            currentQuestion.setDifficulty(question.difficulty());
            currentQuestion.setXpReward(question.xpReward());
        }
    }

    private CourseKnowledgePointRequest.QuestionPayload toPayload(
            CourseRevision revision, TopicRevision topic, LessonRevision lesson, QuestionRevision question) {
        List<CourseKnowledgePointRequest.OptionPayload> options = sortedOptions(question).stream()
                .filter(option -> option.getOptionText() != null && !option.getOptionText().trim().isBlank())
                .limit(8)
                .map(option -> new CourseKnowledgePointRequest.OptionPayload(
                        bounded(option.getOptionText(), 250), option.isCorrect(), option.getOrderIndex()))
                .toList();
        Long topicId = topic.getSourceTopic() == null ? topic.getId() : topic.getSourceTopic().getId();
        Long lessonId = lesson.getSourceLesson() == null ? lesson.getId() : lesson.getSourceLesson().getId();
        return new CourseKnowledgePointRequest.QuestionPayload(
                question.getId(),
                topicId,
                topic.getOrderIndex(),
                bounded(topic.getName(), 255),
                bounded(topic.getDescription(), 1000),
                lessonId,
                lesson.getOrderIndex(),
                bounded(lesson.getTitle(), 255),
                bounded(lesson.getDescription(), 1000),
                bounded(lesson.getContent(), 2000),
                lesson.getSkillType() == null ? "" : lesson.getSkillType().name(),
                question.getOrderIndex(),
                question.getType() == null ? "" : question.getType().name(),
                bounded(question.getQuestionText(), 1000),
                bounded(question.getCorrectAnswer(), 500),
                options);
    }

    private Map<Long, String> validateResponse(CourseKnowledgePointResponse response, Set<Long> expectedIds) {
        if (response == null || response.items() == null) {
            throw enrichmentFailure();
        }
        Map<Long, String> result = new HashMap<>();
        for (CourseKnowledgePointResponse.Item item : response.items()) {
            if (item == null || item.questionId() == null
                    || !expectedIds.contains(item.questionId())
                    || result.containsKey(item.questionId())) {
                throw enrichmentFailure();
            }
            result.put(item.questionId(), normalizeKnowledgePoint(item.knowledgePoint()));
        }
        if (!result.keySet().equals(expectedIds)) {
            throw enrichmentFailure();
        }
        return result;
    }

    private String fingerprint(CourseRevision revision) {
        StringBuilder value = new StringBuilder();
        append(value, revision.getId(), revision.getRevisionNumber(), revision.getName(), revision.getDescription(),
                revision.getType(), revision.getLevel(), revision.getOrderIndex());
        for (TopicRevision topic : sortedTopics(revision)) {
            append(value, topic.getId(), topic.getSourceTopic() == null ? null : topic.getSourceTopic().getId(),
                    topic.getOrderIndex(), topic.getName(), topic.getDescription());
            for (LessonRevision lesson : sortedLessons(topic)) {
                append(value, lesson.getId(), lesson.getSourceLesson() == null ? null : lesson.getSourceLesson().getId(),
                        lesson.getOrderIndex(), lesson.getTitle(), lesson.getDescription(), lesson.getContent(),
                        lesson.getSkillType(), lesson.getAudioUrl(), lesson.getAudioPublicId(), lesson.getXpReward(),
                        lesson.isPlacementTest());
                for (QuestionRevision question : sortedQuestions(lesson)) {
                    append(value, question.getId(),
                            question.getSourceQuestion() == null ? null : question.getSourceQuestion().getId(),
                            question.getOrderIndex(), question.getType(), question.getQuestionText(),
                            question.getExplanation(), question.getCorrectAnswer(), question.getKnowledgePoint(),
                            question.getDifficulty(), question.getXpReward());
                    for (QuestionOptionRevision option : sortedOptions(question)) {
                        append(value, option.getId(),
                                option.getSourceOption() == null ? null : option.getSourceOption().getId(),
                                option.getOrderIndex(), option.getOptionText(), option.isCorrect());
                    }
                }
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void append(StringBuilder target, Object... values) {
        for (Object value : values) {
            target.append(value == null ? "" : value).append('\u001f');
        }
        target.append('\u001e');
    }

    private List<TopicRevision> sortedTopics(CourseRevision revision) {
        return revision.getTopics().stream()
                .sorted(Comparator.comparingInt(TopicRevision::getOrderIndex)
                        .thenComparing(topic -> nullLast(topic.getId()))
                        .thenComparing(topic -> nullLast(topic.getSourceTopic() == null ? null : topic.getSourceTopic().getId())))
                .toList();
    }

    private List<LessonRevision> sortedLessons(TopicRevision topic) {
        return topic.getLessons().stream()
                .sorted(Comparator.comparingInt(LessonRevision::getOrderIndex)
                        .thenComparing(lesson -> nullLast(lesson.getId()))
                        .thenComparing(lesson -> nullLast(lesson.getSourceLesson() == null ? null : lesson.getSourceLesson().getId())))
                .toList();
    }

    private List<QuestionRevision> sortedQuestions(LessonRevision lesson) {
        return lesson.getQuestions().stream()
                .sorted(Comparator.comparingInt(QuestionRevision::getOrderIndex)
                        .thenComparing(question -> nullLast(question.getId()))
                        .thenComparing(question -> nullLast(question.getSourceQuestion() == null ? null : question.getSourceQuestion().getId())))
                .toList();
    }

    private List<QuestionOptionRevision> sortedOptions(QuestionRevision question) {
        return question.getOptions().stream()
                .sorted(Comparator.comparingInt(QuestionOptionRevision::getOrderIndex)
                        .thenComparing(option -> nullLast(option.getId()))
                        .thenComparing(option -> nullLast(option.getSourceOption() == null ? null : option.getSourceOption().getId())))
                .toList();
    }

    private Long nullLast(Long value) {
        return value == null ? Long.MAX_VALUE : value;
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static ApiException enrichmentFailure() {
        return new ApiException(HttpStatus.BAD_GATEWAY, ENRICHMENT_FAILURE_MESSAGE);
    }

    public record CourseRevisionSnapshot(
            Long revisionId,
            int revisionNumber,
            String fingerprint,
            CourseKnowledgePointRequest.CoursePayload course,
            List<QuestionSnapshot> questions) {}

    public record EnrichedCourseRevisionSnapshot(
            CourseRevisionSnapshot snapshot,
            Map<Long, String> knowledgePoints) {}

    public record QuestionSnapshot(
            Long questionId,
            String difficulty,
            int xpReward,
            CourseKnowledgePointRequest.QuestionPayload payload,
            QuestionRevision revision) {}
}
