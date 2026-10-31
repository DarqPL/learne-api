package com.learne.backend.student.learning.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.pagination.CursorPageResponse;
import com.learne.backend.shared.pagination.CursorTokenService;
import com.learne.backend.student.learning.dto.MistakeReviewDtos;
import com.learne.backend.student.learning.dto.SubmitQuestionAnswerRequest;
import com.learne.backend.student.learning.entity.MistakeReviewAnswer;
import com.learne.backend.student.learning.entity.MistakeReviewSession;
import com.learne.backend.student.learning.entity.MistakeReviewSessionQuestion;
import com.learne.backend.student.learning.enums.MistakeReviewSessionStatus;
import com.learne.backend.student.learning.repository.MistakeReviewAnswerRepository;
import com.learne.backend.student.learning.repository.MistakeReviewSessionRepository;
import com.learne.backend.student.learning.repository.MistakeReviewPoolRepository;
import com.learne.backend.student.learning.repository.QuestionAnswerRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class StudentMistakeReviewService {
    private static final String CURSOR_ENDPOINT = "student-mistake-review-questions";
    private static final String CURSOR_SORT = "answerEventOrderDescQuestionIdAsc";

    private final com.learne.backend.student.learning.repository.QuestionAnswerRepository questionAnswerRepository;
    private final MistakeReviewPoolRepository poolRepository;
    private final QuestionRepository questionRepository;
    private final MistakeReviewAnswerRepository reviewAnswerRepository;
    private final MistakeReviewSessionRepository sessionRepository;
    private final StudentMistakeReviewGradingService gradingService;
    private final CursorTokenService cursorTokenService;
    private final ObjectMapper objectMapper;

    public StudentMistakeReviewService(QuestionAnswerRepository questionAnswerRepository,
                                       MistakeReviewPoolRepository poolRepository,
                                       QuestionRepository questionRepository,
                                       MistakeReviewAnswerRepository reviewAnswerRepository,
                                       MistakeReviewSessionRepository sessionRepository,
                                       StudentMistakeReviewGradingService gradingService,
                                       CursorTokenService cursorTokenService,
                                       ObjectMapper objectMapper) {
        this.questionAnswerRepository = questionAnswerRepository;
        this.poolRepository = poolRepository;
        this.questionRepository = questionRepository;
        this.reviewAnswerRepository = reviewAnswerRepository;
        this.sessionRepository = sessionRepository;
        this.gradingService = gradingService;
        this.cursorTokenService = cursorTokenService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CursorPageResponse<MistakeReviewDtos.Question> listQuestions(User student, Integer requestedSize, String cursor) {
        int size = requestedSize == null ? 20 : Math.max(1, Math.min(requestedSize, 50));
        CursorValues cursorValues = decodeCursor(cursor);
        List<PoolItem> page = loadPool(student, cursorValues.eventOrder(), cursorValues.questionId(), size + 1);
        boolean hasNext = page.size() > size;
        if (hasNext) {
            page = page.subList(0, size);
        }
        String nextCursor = hasNext
                ? cursorTokenService.encode(CURSOR_ENDPOINT, CURSOR_SORT, Map.of(),
                        Map.of("answerEventOrder", page.get(page.size() - 1).eventOrder(),
                               "questionId", page.get(page.size() - 1).question().getId()))
                : null;
        return new CursorPageResponse<>(page.stream().map(item -> safeQuestion(item.question())).toList(),
                nextCursor, hasNext, page.size());
    }

    @Transactional
    public MistakeReviewDtos.CreateResponse createSession(User student) {
        List<PoolItem> selected = loadPool(student, null, null, 10);
        if (selected.isEmpty()) {
            return new MistakeReviewDtos.CreateResponse(null, 0, List.of());
        }
        MistakeReviewSession session = new MistakeReviewSession();
        session.setUser(student);
        session.setStatus(MistakeReviewSessionStatus.IN_PROGRESS);
        session.setQuestionCount(selected.size());
        for (int index = 0; index < selected.size(); index++) {
            Question question = selected.get(index).question();
            MistakeReviewSessionQuestion membership = new MistakeReviewSessionQuestion();
            membership.setSession(session);
            membership.setQuestion(question);
            membership.setSourceQuestionIdSnapshot(question.getId());
            membership.setQuestionOrder(index);
            membership.setQuestionText(question.getQuestionText());
            membership.setQuestionType(question.getType().name());
            membership.setOptionsSnapshot(serializeOptions(question));
            membership.setCorrectAnswerSnapshot(question.getCorrectAnswer());
            membership.setExplanationSnapshot(question.getExplanation());
            session.getQuestions().add(membership);
        }
        MistakeReviewSession saved = sessionRepository.save(session);
        return new MistakeReviewDtos.CreateResponse(saved.getId(), saved.getQuestionCount(),
                saved.getQuestions().stream().map(this::safeQuestion).toList());
    }

    @Transactional
    public MistakeReviewDtos.SessionResponse getSession(User student, Long sessionId) {
        MistakeReviewSession session = ownedSession(student, sessionId);
        List<MistakeReviewSessionQuestion> questions =
                session.getQuestions().stream().sorted(Comparator.comparingInt(MistakeReviewSessionQuestion::getQuestionOrder)).toList();
        List<MistakeReviewDtos.Question> safeQuestions = questions.stream().map(this::safeQuestion).toList();
        List<MistakeReviewDtos.AnswerResult> answers = session.getStatus() == MistakeReviewSessionStatus.SUBMITTED
                ? questions.stream().map(this::answerResult).toList() : List.of();
        return new MistakeReviewDtos.SessionResponse(session.getId(), session.getQuestionCount(), safeQuestions,
                session.getScorePercent(), answers);
    }

    @Transactional
    public MistakeReviewDtos.Result submit(User student, Long sessionId,
                                           MistakeReviewDtos.SubmitRequest request) {
        MistakeReviewSession session = sessionRepository.findByIdAndUserForUpdate(sessionId, student)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Review session not found."));
        List<MistakeReviewSessionQuestion> questions =
                session.getQuestions().stream().sorted(Comparator.comparingInt(MistakeReviewSessionQuestion::getQuestionOrder)).toList();
        List<SubmitQuestionAnswerRequest> answers = request == null || request.answers() == null
                ? List.of() : request.answers();
        validateMembership(questions, answers);
        String hash = hash(answers);
        if (session.getStatus() == MistakeReviewSessionStatus.SUBMITTED) {
            if (hash.equals(session.getRequestHash())) {
                return result(session, questions);
            }
            throw new ApiException(HttpStatus.CONFLICT, "Review session has already been submitted.");
        }

        Map<Long, SubmitQuestionAnswerRequest> byQuestion = answers.stream()
                .collect(LinkedHashMap::new, (map, answer) -> map.put(answer.questionId(), answer), Map::putAll);
        List<MistakeReviewAnswer> persisted = new ArrayList<>();
        for (MistakeReviewSessionQuestion question : questions) {
            StudentMistakeReviewGradingService.Result graded =
                    gradingService.grade(snapshot(question), byQuestion.get(question.getSourceQuestionIdSnapshot()));
            MistakeReviewAnswer answer = new MistakeReviewAnswer();
            answer.setSessionQuestion(question);
            SubmitQuestionAnswerRequest submitted = byQuestion.get(question.getSourceQuestionIdSnapshot());
            answer.setAnswerText(submitted.answerText());
            answer.setSelectedOptionId(submitted.selectedOptionId());
            answer.setCorrect(graded.correct());
            answer.setScorePercent(graded.scorePercent());
            answer.setGradingMethod(graded.gradingMethod());
            answer.setFeedback(graded.feedback());
            answer.setCorrectAnswerDisplay(graded.correctAnswerDisplay());
            answer.setExplanationSnapshot(graded.explanation());
            answer.setAnswerEventOrder(questionAnswerRepository.nextAnswerEventOrder());
            persisted.add(answer);
        }
        reviewAnswerRepository.saveAll(persisted);
        int score = (int) Math.round(persisted.stream().filter(MistakeReviewAnswer::isCorrect).count() * 100.0 / persisted.size());
        session.setStatus(MistakeReviewSessionStatus.SUBMITTED);
        session.setScorePercent(score);
        session.setRequestHash(hash);
        session.setSubmittedAt(java.time.Instant.now());
        sessionRepository.save(session);
        return new MistakeReviewDtos.Result(session.getId(), session.getQuestionCount(), score,
                persisted.stream().map(this::answerResult).toList());
    }

    private List<PoolItem> loadPool(User student, Long cursorEventOrder,
                                    Long cursorQuestionId, int limit) {
        List<MistakeReviewPoolRepository.LatestEvent> events = poolRepository.findPageByUser(
                student.getId(), cursorEventOrder, cursorQuestionId, limit);
        if (events.isEmpty()) {
            return List.of();
        }
        Map<Long, Question> questionsById = new HashMap<>();
        for (Question question : questionRepository.findAllByIdWithContent(
                events.stream().map(MistakeReviewPoolRepository.LatestEvent::questionId).toList())) {
            questionsById.put(question.getId(), question);
        }
        return events.stream()
                .filter(event -> questionsById.containsKey(event.questionId()))
                .map(event -> new PoolItem(questionsById.get(event.questionId()),
                        event.answerEventOrder(), event.correct()))
                .toList();
    }

    private CursorValues decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new CursorValues(null, null);
        }
        CursorTokenService.DecodedCursor decoded = cursorTokenService.decode(
                cursor, CURSOR_ENDPOINT, CURSOR_SORT, Map.of());
        return new CursorValues(
                parseLong(decoded.sortValues().get("answerEventOrder")),
                parseLong(decoded.sortValues().get("questionId")));
    }

    private MistakeReviewSession ownedSession(User student, Long id) {
        return sessionRepository.findByIdAndUser(id, student)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Review session not found."));
    }

    private void validateMembership(List<MistakeReviewSessionQuestion> questions,
                                    List<SubmitQuestionAnswerRequest> answers) {
        Set<Long> expected = questions.stream().map(MistakeReviewSessionQuestion::getSourceQuestionIdSnapshot)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> actual = new HashSet<>();
        for (SubmitQuestionAnswerRequest answer : answers) {
            if (answer == null || answer.questionId() == null || !actual.add(answer.questionId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Answers must contain each session question exactly once.");
            }
        }
        if (!expected.equals(actual)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Answers must contain each session question exactly once.");
        }
    }

    private StudentMistakeReviewGradingService.Snapshot snapshot(MistakeReviewSessionQuestion question) {
        try {
            List<StudentMistakeReviewGradingService.Option> options = new ArrayList<>();
            JsonNode root = objectMapper.readTree(question.getOptionsSnapshot());
            for (JsonNode option : root) {
                options.add(new StudentMistakeReviewGradingService.Option(
                        option.get("id").asLong(), option.get("text").asText(), option.get("correct").asBoolean()));
            }
            return new StudentMistakeReviewGradingService.Snapshot(question.getSourceQuestionIdSnapshot(),
                    question.getQuestionType(), question.getCorrectAnswerSnapshot(), options,
                    question.getExplanationSnapshot());
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid stored review snapshot.", exception);
        }
    }

    private MistakeReviewDtos.Question safeQuestion(Question question) {
        if (question == null) {
            throw new IllegalStateException("Review snapshot source question is unavailable.");
        }
        return new MistakeReviewDtos.Question(question.getId(), question.getQuestionText(), question.getType().name(),
                question.getOptions().stream().filter(QuestionOption::isActive)
                        .sorted(Comparator.comparingInt(QuestionOption::getOrderIndex))
                        .map(option -> new MistakeReviewDtos.Option(option.getId(), option.getOptionText(), option.getOrderIndex()))
                        .toList(),
                question.getLesson().getTopic().getCourse().getId(),
                question.getLesson().getTopic().getCourse().getName(),
                question.getLesson().getId(), question.getLesson().getTitle());
    }

    private MistakeReviewDtos.Question safeQuestion(MistakeReviewSessionQuestion question) {
        return new MistakeReviewDtos.Question(question.getSourceQuestionIdSnapshot(),
                question.getQuestionText(), question.getQuestionType(), safeOptions(question.getOptionsSnapshot()),
                question.getQuestion() == null ? null : question.getQuestion().getLesson().getTopic().getCourse().getId(),
                question.getQuestion() == null ? null : question.getQuestion().getLesson().getTopic().getCourse().getName(),
                question.getQuestion() == null ? null : question.getQuestion().getLesson().getId(),
                question.getQuestion() == null ? null : question.getQuestion().getLesson().getTitle());
    }

    private List<MistakeReviewDtos.Option> safeOptions(String json) {
        try {
            List<MistakeReviewDtos.Option> options = new ArrayList<>();
            for (JsonNode option : objectMapper.readTree(json)) {
                options.add(new MistakeReviewDtos.Option(option.get("id").asLong(), option.get("text").asText(),
                        option.get("order").asInt()));
            }
            return options;
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid stored review options snapshot.", exception);
        }
    }

    private String serializeOptions(Question question) {
        try {
            return objectMapper.writeValueAsString(question.getOptions().stream().filter(QuestionOption::isActive)
                    .map(option -> Map.of("id", option.getId(), "text", option.getOptionText(),
                            "order", option.getOrderIndex(), "correct", option.isCorrect())).toList());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize review options.", exception);
        }
    }

    private MistakeReviewDtos.AnswerResult answerResult(MistakeReviewSessionQuestion question) {
        MistakeReviewAnswer answer = question.getAnswer();
        return new MistakeReviewDtos.AnswerResult(question.getSourceQuestionIdSnapshot(),
                answer.isCorrect(), answer.getScorePercent(), answer.getFeedback(),
                answer.getCorrectAnswerDisplay(), answer.getExplanationSnapshot());
    }

    private MistakeReviewDtos.AnswerResult answerResult(MistakeReviewAnswer answer) {
        MistakeReviewSessionQuestion question = answer.getSessionQuestion();
        return new MistakeReviewDtos.AnswerResult(question.getSourceQuestionIdSnapshot(),
                answer.isCorrect(), answer.getScorePercent(), answer.getFeedback(),
                answer.getCorrectAnswerDisplay(), answer.getExplanationSnapshot());
    }

    private MistakeReviewDtos.Result result(MistakeReviewSession session,
                                            List<MistakeReviewSessionQuestion> questions) {
        return new MistakeReviewDtos.Result(session.getId(), session.getQuestionCount(), session.getScorePercent(),
                questions.stream().map(this::answerResult).toList());
    }

    private String hash(List<SubmitQuestionAnswerRequest> answers) {
        String canonical = answers.stream().sorted(Comparator.comparing(SubmitQuestionAnswerRequest::questionId))
                .map(answer -> answer.questionId() + "|" + String.valueOf(answer.selectedOptionId())
                        + "|" + normalize(answer.answerText())).collect(java.util.stream.Collectors.joining(";"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash review submission.", exception);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid cursor.");
        }
    }

    private record PoolItem(Question question, long eventOrder, boolean correct) {
    }

    private record CursorValues(Long eventOrder, Long questionId) {
    }
}
