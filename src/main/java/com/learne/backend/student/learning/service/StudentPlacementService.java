package com.learne.backend.student.learning.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.dto.PlacementAttemptResponse;
import com.learne.backend.student.learning.dto.PlacementQuestionResponse;
import com.learne.backend.student.learning.dto.PlacementSkillStatResponse;
import com.learne.backend.student.learning.dto.PlacementStatusResponse;
import com.learne.backend.student.learning.dto.SubmitPlacementAnswerRequest;
import com.learne.backend.student.learning.dto.SubmitPlacementRequest;
import com.learne.backend.student.learning.dto.SubmitPlacementResponse;
import com.learne.backend.student.learning.dto.StudentQuizOptionResponse;
import com.learne.backend.student.learning.entity.PlacementAttempt;
import com.learne.backend.student.learning.entity.PlacementAttemptAnswer;
import com.learne.backend.student.learning.entity.PlacementAttemptQuestion;
import com.learne.backend.student.learning.entity.PlacementPreference;
import com.learne.backend.student.learning.enums.PlacementAttemptStatus;
import com.learne.backend.student.learning.repository.PlacementAttemptAnswerRepository;
import com.learne.backend.student.learning.repository.PlacementAttemptQuestionRepository;
import com.learne.backend.student.learning.repository.PlacementAttemptRepository;
import com.learne.backend.student.learning.repository.PlacementPreferenceRepository;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.enums.SkillType;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StudentPlacementService {
    private static final int DURATION_SECONDS = 900;
    private static final Map<String, Integer> SKILL_QUOTAS = new LinkedHashMap<>();

    static {
        SKILL_QUOTAS.put("VOCABULARY", 5);
        SKILL_QUOTAS.put("GRAMMAR", 5);
        SKILL_QUOTAS.put("READING", 5);
        SKILL_QUOTAS.put("WRITING", 5);
    }

    private final PlacementAttemptRepository placementAttemptRepository;
    private final PlacementAttemptAnswerRepository placementAttemptAnswerRepository;
    private final PlacementAttemptQuestionRepository placementAttemptQuestionRepository;
    private final PlacementPreferenceRepository placementPreferenceRepository;
    private final QuestionRepository questionRepository;
    private final TransactionTemplate transactionTemplate;

    public StudentPlacementService(PlacementAttemptRepository placementAttemptRepository,
                                   PlacementAttemptAnswerRepository placementAttemptAnswerRepository,
                                   PlacementAttemptQuestionRepository placementAttemptQuestionRepository,
                                   PlacementPreferenceRepository placementPreferenceRepository,
                                   QuestionRepository questionRepository,
                                   PlatformTransactionManager transactionManager) {
        this.placementAttemptRepository = placementAttemptRepository;
        this.placementAttemptAnswerRepository = placementAttemptAnswerRepository;
        this.placementAttemptQuestionRepository = placementAttemptQuestionRepository;
        this.placementPreferenceRepository = placementPreferenceRepository;
        this.questionRepository = questionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PlacementStatusResponse status(User student) {
        boolean completed = placementAttemptRepository
                .findByUserAndStatus(student, PlacementAttemptStatus.SUBMITTED).stream()
                .max(Comparator.comparing(PlacementAttempt::getSubmittedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PlacementAttempt::getStartedAt))
                .isPresent();
        PlacementAttempt activeAttempt = placementAttemptRepository
                .findFirstByUserAndStatusOrderByStartedAtDesc(student, PlacementAttemptStatus.IN_PROGRESS)
                .filter(attempt -> !isExpired(attempt, Instant.now()))
                .orElse(null);
        boolean skipped = placementPreferenceRepository.findByUser(student)
                .map(preference -> preference.getSkippedAt() != null)
                .orElse(false);
        boolean canGenerateRecommendations = completed;
        String reason = completed ? null : skipped ? "PLACEMENT_SKIPPED" : "PLACEMENT_REQUIRED";
        return new PlacementStatusResponse(completed, activeAttempt == null ? null : activeAttempt.getId(), skipped,
                canGenerateRecommendations, reason);
    }

    @Transactional
    public PlacementStatusResponse skip(User student) {
        PlacementPreference preference = placementPreferenceRepository.findByUser(student).orElseGet(() -> {
            PlacementPreference created = new PlacementPreference();
            created.setUser(student);
            return created;
        });
        preference.setSkippedAt(Instant.now());
        placementPreferenceRepository.save(preference);
        return status(student);
    }

    @Transactional
    public PlacementAttemptResponse start(User student) {
        Instant now = Instant.now();
        PlacementAttempt activeAttempt = placementAttemptRepository
                .findFirstByUserAndStatusOrderByStartedAtDesc(student, PlacementAttemptStatus.IN_PROGRESS)
                .orElse(null);
        if (activeAttempt != null && !isExpired(activeAttempt, now)) {
            return toAttemptResponse(activeAttempt, now);
        }
        if (activeAttempt != null) {
            activeAttempt.setStatus(PlacementAttemptStatus.EXPIRED);
            placementAttemptRepository.save(activeAttempt);
        }

        PlacementAttempt attempt = new PlacementAttempt();
        attempt.setUser(student);
        attempt.setStatus(PlacementAttemptStatus.IN_PROGRESS);
        attempt.setStartedAt(now);
        attempt.setExpiresAt(now.plusSeconds(DURATION_SECONDS));
        attempt.setScorePercent(0);
        attempt = placementAttemptRepository.save(attempt);

        List<PlacementAttemptQuestion> attemptQuestions = new ArrayList<>();
        int sequenceOrder = 1;
        for (Map.Entry<String, Integer> quota : SKILL_QUOTAS.entrySet()) {
            List<Question> questions = questionRepository.findApprovedPlacementQuestionsByCourseType(
                    CourseType.valueOf(quota.getKey()), SkillType.valueOf(quota.getKey()), ContentStatus.APPROVED)
                    .stream()
                    .filter(question -> question.getType() != QuestionType.PRONUNCIATION)
                    .toList();
            if (questions.size() < quota.getValue()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Not enough approved placement questions.");
            }
            List<Question> selected = new ArrayList<>(questions);
            Collections.shuffle(selected, new Random((student.getId() * 31) + quota.getKey().hashCode()));
            selected = new ArrayList<>(selected.subList(0, quota.getValue()));
            selected.sort(Comparator.comparingInt(Question::getOrderIndex).thenComparing(Question::getId));
            for (Question question : selected) {
                PlacementAttemptQuestion attemptQuestion = new PlacementAttemptQuestion();
                attemptQuestion.setPlacementAttempt(attempt);
                attemptQuestion.setQuestion(question);
                attemptQuestion.setSkill(quota.getKey());
                attemptQuestion.setSequenceOrder(sequenceOrder++);
                attemptQuestions.add(attemptQuestion);
            }
        }
        placementAttemptQuestionRepository.saveAll(attemptQuestions);
        return toAttemptResponse(attempt, now);
    }

    @Transactional(readOnly = true)
    public PlacementAttemptResponse getAttempt(User student, Long attemptId) {
        PlacementAttempt attempt = placementAttemptRepository.findByIdAndUser(attemptId, student)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Placement attempt not found."));
        return toAttemptResponse(attempt, Instant.now());
    }

    public SubmitPlacementResponse submit(User student, Long attemptId, SubmitPlacementRequest request) {
        SubmitResult result = transactionTemplate.execute(status -> submitInTransaction(student, attemptId, request));
        if (result.expired()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Placement attempt has expired.");
        }
        return result.response();
    }

    private SubmitResult submitInTransaction(User student, Long attemptId, SubmitPlacementRequest request) {
        Instant now = Instant.now();
        PlacementAttempt attempt = placementAttemptRepository.findByIdAndUserForUpdate(attemptId, student)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Placement attempt not found."));
        if (attempt.getStatus() != PlacementAttemptStatus.IN_PROGRESS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Placement attempt is not in progress.");
        }
        if (isExpired(attempt, now)) {
            attempt.setStatus(PlacementAttemptStatus.EXPIRED);
            placementAttemptRepository.save(attempt);
            return new SubmitResult(null, true);
        }

        List<PlacementAttemptQuestion> attemptQuestions = placementAttemptQuestionRepository
                .findByPlacementAttemptOrderBySequenceOrderAsc(attempt);
        Map<Long, PlacementAttemptQuestion> attemptQuestionsByQuestionId = attemptQuestions.stream()
                .collect(Collectors.toMap(item -> item.getQuestion().getId(), Function.identity()));
        Map<Long, SubmitPlacementAnswerRequest> answersByQuestionId = answersByQuestionId(request);
        for (Long questionId : answersByQuestionId.keySet()) {
            if (!attemptQuestionsByQuestionId.containsKey(questionId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Question does not belong to this placement attempt.");
            }
        }

        List<PlacementAttemptAnswer> savedAnswers = new ArrayList<>();
        Map<String, SkillStat> statsBySkill = new LinkedHashMap<>();
        int correctCount = 0;
        for (PlacementAttemptQuestion attemptQuestion : attemptQuestions) {
            Question question = attemptQuestion.getQuestion();
            if (question.getType() == QuestionType.PRONUNCIATION) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Pronunciation questions are not supported for placement submit.");
            }
            SubmitPlacementAnswerRequest answerRequest = answersByQuestionId.get(question.getId());
            GradedAnswer graded = grade(question, answerRequest);
            if (graded.correct()) {
                correctCount++;
            }

            SkillStat stat = statsBySkill.computeIfAbsent(attemptQuestion.getSkill(), SkillStat::new);
            stat.totalCount++;
            if (graded.correct()) {
                stat.correctCount++;
            } else if (question.getKnowledgePoint() != null && !question.getKnowledgePoint().isBlank()) {
                stat.weakKnowledgePoints.add(question.getKnowledgePoint().trim());
            }

            PlacementAttemptAnswer answer = new PlacementAttemptAnswer();
            answer.setPlacementAttempt(attempt);
            answer.setQuestion(question);
            answer.setAnswerText(answerRequest == null ? null : answerRequest.answerText());
            answer.setSelectedOption(graded.selectedOption());
            answer.setSelectedOptionIdsJson(selectedOptionIdsJson(answerRequest));
            answer.setCorrect(graded.correct());
            answer.setScorePercent(graded.correct() ? 100 : 0);
            savedAnswers.add(answer);
        }

        int scorePercent = attemptQuestions.isEmpty() ? 0 : (int) Math.round((correctCount * 100.0) / attemptQuestions.size());
        placementAttemptAnswerRepository.saveAll(savedAnswers);
        attempt.setStatus(PlacementAttemptStatus.SUBMITTED);
        attempt.setSubmittedAt(now);
        attempt.setScorePercent(scorePercent);
        placementAttemptRepository.save(attempt);

        return new SubmitResult(new SubmitPlacementResponse(attempt.getId(), attempt.getStatus().name(), scorePercent,
                statsBySkill.values().stream().map(SkillStat::toResponse).toList()), false);
    }

    private Map<Long, SubmitPlacementAnswerRequest> answersByQuestionId(SubmitPlacementRequest request) {
        Map<Long, SubmitPlacementAnswerRequest> answersByQuestionId = new LinkedHashMap<>();
        for (SubmitPlacementAnswerRequest answer : request.answers()) {
            if (answer == null || answer.questionId() == null || answersByQuestionId.put(answer.questionId(), answer) != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Answer is required for every question.");
            }
            if (answer.selectedOptionIds() != null && !answer.selectedOptionIds().isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Multiple selected options are not supported for placement submit.");
            }
        }
        return answersByQuestionId;
    }

    private GradedAnswer grade(Question question, SubmitPlacementAnswerRequest answer) {
        if (usesOptionGrading(question)) {
            if (answer == null || answer.selectedOptionId() == null) {
                return new GradedAnswer(false, null);
            }
            QuestionOption selectedOption = question.getOptions().stream()
                    .filter(option -> option.getId().equals(answer.selectedOptionId()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Option does not belong to this question."));
            return new GradedAnswer(selectedOption.isCorrect(), selectedOption);
        }
        if (answer == null || answer.answerText() == null || answer.answerText().isBlank()) {
            return new GradedAnswer(false, null);
        }
        return new GradedAnswer(normalize(question.getCorrectAnswer()).equals(normalize(answer.answerText())), null);
    }

    private boolean usesOptionGrading(Question question) {
        return question.getType() == QuestionType.MULTIPLE_CHOICE
                || (question.getType() == QuestionType.TRUE_FALSE && !question.getOptions().isEmpty());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String selectedOptionIdsJson(SubmitPlacementAnswerRequest answer) {
        if (answer == null || answer.selectedOptionIds() == null) {
            return null;
        }
        return answer.selectedOptionIds().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private boolean isExpired(PlacementAttempt attempt, Instant now) {
        return !attempt.getExpiresAt().isAfter(now);
    }

    private PlacementAttemptResponse toAttemptResponse(PlacementAttempt attempt, Instant now) {
        List<PlacementAttemptQuestion> questions = placementAttemptQuestionRepository
                .findByPlacementAttemptOrderBySequenceOrderAsc(attempt);
        long remainingSeconds = Math.max(0, Duration.between(now, attempt.getExpiresAt()).getSeconds());
        return new PlacementAttemptResponse(attempt.getId(), attempt.getStatus().name(), attempt.getStartedAt(),
                attempt.getExpiresAt(), remainingSeconds, DURATION_SECONDS,
                questions.stream().map(this::toQuestionResponse).toList());
    }

    private PlacementQuestionResponse toQuestionResponse(PlacementAttemptQuestion attemptQuestion) {
        Question question = attemptQuestion.getQuestion();
        return new PlacementQuestionResponse(question.getId(), question.getType().name(), question.getQuestionText(),
                question.getKnowledgePoint(), attemptQuestion.getSkill(), attemptQuestion.getSequenceOrder(),
                question.getOptions().stream()
                        .sorted(Comparator.comparingInt(QuestionOption::getOrderIndex).thenComparing(QuestionOption::getId))
                        .map(StudentQuizOptionResponse::from)
                        .toList());
    }

    private record GradedAnswer(boolean correct, QuestionOption selectedOption) {}

    private record SubmitResult(SubmitPlacementResponse response, boolean expired) {}

    private static class SkillStat {
        private final String skill;
        private int correctCount;
        private int totalCount;
        private final Set<String> weakKnowledgePoints = new LinkedHashSet<>();

        private SkillStat(String skill) {
            this.skill = skill;
        }

        private PlacementSkillStatResponse toResponse() {
            int scorePercent = totalCount == 0 ? 0 : (int) Math.round((correctCount * 100.0) / totalCount);
            return new PlacementSkillStatResponse(skill, correctCount, totalCount, scorePercent,
                    new ArrayList<>(weakKnowledgePoints));
        }
    }
}
