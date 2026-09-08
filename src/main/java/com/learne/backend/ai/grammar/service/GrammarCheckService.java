package com.learne.backend.ai.grammar.service;

import com.learne.backend.ai.grammar.ai.AiGrammarCheckClient;
import com.learne.backend.ai.grammar.ai.AiGrammarCheckRequest;
import com.learne.backend.ai.grammar.ai.AiGrammarCheckResponse;
import com.learne.backend.ai.grammar.dto.GrammarCheckContextRequest;
import com.learne.backend.ai.grammar.dto.GrammarCheckErrorResponse;
import com.learne.backend.ai.grammar.dto.GrammarCheckListItemResponse;
import com.learne.backend.ai.grammar.dto.GrammarCheckResponse;
import com.learne.backend.ai.grammar.dto.SubmitGrammarCheckRequest;
import com.learne.backend.ai.grammar.entity.GrammarCheck;
import com.learne.backend.ai.grammar.entity.GrammarCheckError;
import com.learne.backend.ai.grammar.enums.GrammarCheckErrorType;
import com.learne.backend.ai.grammar.enums.GrammarCheckStatus;
import com.learne.backend.ai.grammar.repository.GrammarCheckErrorRepository;
import com.learne.backend.ai.grammar.repository.GrammarCheckRepository;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.enums.EnrollmentStatus;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GrammarCheckService {
    private static final int MAX_INPUT_LENGTH = 2000;
    private static final int MAX_ERRORS = 20;
    private static final int PREVIEW_LENGTH = 120;
    private static final int MAX_PERSISTED_TEXT_LENGTH = 500;
    private static final Set<EnrollmentStatus> ACCESSIBLE_ENROLLMENT_STATUSES = Set.of(
            EnrollmentStatus.ACTIVE, EnrollmentStatus.COMPLETED);

    private final GrammarCheckRepository grammarCheckRepository;
    private final GrammarCheckErrorRepository grammarCheckErrorRepository;
    private final AiGrammarCheckClient aiGrammarCheckClient;
    private final CourseEnrollmentRepository courseEnrollmentRepository;
    private final LessonRepository lessonRepository;
    private final QuestionRepository questionRepository;
    private final EntityManager entityManager;

    public GrammarCheckService(
            GrammarCheckRepository grammarCheckRepository,
            GrammarCheckErrorRepository grammarCheckErrorRepository,
            AiGrammarCheckClient aiGrammarCheckClient) {
        this(grammarCheckRepository, grammarCheckErrorRepository, aiGrammarCheckClient, null, null, null, null);
    }

    @Autowired
    public GrammarCheckService(
            GrammarCheckRepository grammarCheckRepository,
            GrammarCheckErrorRepository grammarCheckErrorRepository,
            AiGrammarCheckClient aiGrammarCheckClient,
            CourseEnrollmentRepository courseEnrollmentRepository,
            LessonRepository lessonRepository,
            QuestionRepository questionRepository,
            EntityManager entityManager) {
        this.grammarCheckRepository = grammarCheckRepository;
        this.grammarCheckErrorRepository = grammarCheckErrorRepository;
        this.aiGrammarCheckClient = aiGrammarCheckClient;
        this.courseEnrollmentRepository = courseEnrollmentRepository;
        this.lessonRepository = lessonRepository;
        this.questionRepository = questionRepository;
        this.entityManager = entityManager;
    }

    @Transactional(noRollbackFor = GrammarCheckFailedException.class)
    public GrammarCheckResponse submit(User user, SubmitGrammarCheckRequest request) {
        validateUser(user);
        String inputText = validateInput(request);
        GrammarCheckContextRequest context = request == null ? null : request.context();
        if (user.getRole() == UserRole.TEACHER && context != null && context.hasAnyValue()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Teacher grammar checks cannot include learning context");
        }

        GrammarCheck check = new GrammarCheck();
        check.setUser(user);
        check.setStatus(GrammarCheckStatus.PENDING);
        check.setInputText(inputText);
        applyContext(check, user, context);
        check = grammarCheckRepository.save(check);

        try {
            AiGrammarCheckResponse aiResponse = aiGrammarCheckClient.checkGrammar(AiGrammarCheckRequest.create(inputText));
            check.getErrors().clear();
            check.getErrors().addAll(normalizeErrors(check, inputText, aiResponse));
            check.setStatus(GrammarCheckStatus.SUCCEEDED);
            check.setCompletedAt(Instant.now());
            check = grammarCheckRepository.save(check);
            return toResponse(check, check.getErrors());
        } catch (RuntimeException exception) {
            String message = safeFailureMessage(exception);
            HttpStatus status = exception instanceof ApiException apiException
                    ? apiException.getStatus()
                    : HttpStatus.BAD_GATEWAY;
            check.setStatus(GrammarCheckStatus.FAILED);
            check.setFailureMessage(message);
            check.setCompletedAt(Instant.now());
            grammarCheckRepository.save(check);
            throw new GrammarCheckFailedException(status, message, check.getId());
        }
    }

    @Transactional(readOnly = true)
    public Page<GrammarCheckListItemResponse> list(User user, Pageable pageable) {
        validateUser(user);
        return grammarCheckRepository.findByUserOrderByCreatedAtDesc(user, pageable).map(this::toListItem);
    }

    @Transactional(readOnly = true)
    public GrammarCheckResponse get(User user, Long id) {
        validateUser(user);
        GrammarCheck check = grammarCheckRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Grammar check not found"));
        return toResponse(check, grammarCheckErrorRepository.findByGrammarCheckOrderBySequenceOrderAsc(check));
    }

    private void validateUser(User user) {
        if (user == null || user.getRole() == null || (user.getRole() != UserRole.STUDENT && user.getRole() != UserRole.TEACHER)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Grammar check is available to students and teachers only");
        }
    }

    private String validateInput(SubmitGrammarCheckRequest request) {
        String inputText = request == null ? null : request.inputText();
        String trimmed = inputText == null ? "" : inputText.trim();
        if (trimmed.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "inputText is required");
        }
        if (trimmed.length() > MAX_INPUT_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "inputText must be at most 2000 characters");
        }
        return trimmed;
    }

    private void applyContext(GrammarCheck check, User user, GrammarCheckContextRequest context) {
        if (context == null || !context.hasAnyValue()) {
            return;
        }
        if (entityManager == null || courseEnrollmentRepository == null || lessonRepository == null || questionRepository == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid grammar check learning context");
        }

        CourseEnrollment enrollment = null;
        Course course = null;
        Lesson lesson = null;
        Question question = null;

        if (context.enrollmentId() != null) {
            enrollment = courseEnrollmentRepository.findByIdAndUserAndStatusInWithCourse(
                            context.enrollmentId(), user, ACCESSIBLE_ENROLLMENT_STATUSES)
                    .orElseThrow(() -> invalidContext());
            course = enrollment.getCourse();
        }
        if (context.courseId() != null) {
            if (course != null && !sameId(course.getId(), context.courseId())) {
                throw invalidContext();
            }
            if (course == null) {
                course = entityManager.find(Course.class, context.courseId());
                if (course == null) {
                    throw invalidContext();
                }
            }
        }
        if (context.lessonId() != null) {
            lesson = lessonRepository.findByIdWithTopicAndCourse(context.lessonId())
                    .orElseThrow(() -> invalidContext());
            if (lesson.getStatus() != ContentStatus.APPROVED) {
                throw invalidContext();
            }
            Course lessonCourse = lesson.getTopic().getCourse();
            if (course != null && !sameId(lessonCourse.getId(), course.getId())) {
                throw invalidContext();
            }
            if (course == null) {
                course = lessonCourse;
            }
        }
        if (context.questionId() != null) {
            question = questionRepository.findByIdWithLesson(context.questionId())
                    .orElseThrow(() -> invalidContext());
            if (question.getStatus() != ContentStatus.APPROVED) {
                throw invalidContext();
            }
            Lesson questionLesson = question.getLesson();
            if (questionLesson.getStatus() != ContentStatus.APPROVED) {
                throw invalidContext();
            }
            if (lesson != null && !sameId(questionLesson.getId(), lesson.getId())) {
                throw invalidContext();
            }
            Course questionCourse = questionLesson.getTopic().getCourse();
            if (course != null && !sameId(questionCourse.getId(), course.getId())) {
                throw invalidContext();
            }
            if (lesson == null) {
                lesson = questionLesson;
            }
            if (course == null) {
                course = questionCourse;
            }
        }
        if (course != null && course.getStatus() != ContentStatus.APPROVED) {
            throw invalidContext();
        }
        if (course != null && enrollment == null && !courseEnrollmentRepository.existsByUserAndCourseAndStatusIn(
                user, course, ACCESSIBLE_ENROLLMENT_STATUSES)) {
            throw invalidContext();
        }

        check.setCourse(course);
        check.setLesson(lesson);
        check.setQuestion(question);
        check.setEnrollment(enrollment);
    }

    private ApiException invalidContext() {
        return new ApiException(HttpStatus.BAD_REQUEST, "Invalid grammar check learning context");
    }

    private boolean sameId(Long left, Long right) {
        return left != null && left.equals(right);
    }

    private List<GrammarCheckError> normalizeErrors(GrammarCheck check, String inputText, AiGrammarCheckResponse aiResponse) {
        if (aiResponse == null || aiResponse.errors() == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI returned invalid response shape");
        }
        List<AiGrammarCheckResponse.ErrorPayload> errors = aiResponse.errors();
        List<AiGrammarCheckResponse.ErrorPayload> valid = errors.stream()
                .filter(error -> isValid(error, inputText))
                .sorted(Comparator.comparing(AiGrammarCheckResponse.ErrorPayload::startPos)
                        .thenComparing(AiGrammarCheckResponse.ErrorPayload::endPos))
                .limit(MAX_ERRORS)
                .toList();

        List<GrammarCheckError> normalized = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            AiGrammarCheckResponse.ErrorPayload payload = valid.get(i);
            GrammarCheckError error = new GrammarCheckError();
            error.setGrammarCheck(check);
            error.setSequenceOrder(i + 1);
            error.setErrorText(payload.errorText().trim());
            error.setErrorType(GrammarCheckErrorType.valueOf(payload.errorType()));
            error.setSuggestion(payload.suggestion().trim());
            error.setExplanation(payload.explanation().trim());
            error.setStartPos(payload.startPos());
            error.setEndPos(payload.endPos());
            normalized.add(error);
        }
        return normalized;
    }

    private boolean isValid(AiGrammarCheckResponse.ErrorPayload error, String inputText) {
        if (error == null
                || isBlank(error.errorText())
                || isBlank(error.errorType())
                || isBlank(error.suggestion())
                || isBlank(error.explanation())
                || error.errorText().trim().length() > MAX_PERSISTED_TEXT_LENGTH
                || error.suggestion().trim().length() > MAX_PERSISTED_TEXT_LENGTH
                || error.explanation().trim().length() > MAX_PERSISTED_TEXT_LENGTH
                || error.startPos() == null
                || error.endPos() == null
                || error.startPos() < 0
                || error.endPos() <= error.startPos()
                || error.endPos() > inputText.length()) {
            return false;
        }
        try {
            GrammarCheckErrorType.valueOf(error.errorType());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return inputText.substring(error.startPos(), error.endPos()).equals(error.errorText());
    }

    private GrammarCheckListItemResponse toListItem(GrammarCheck check) {
        String inputText = check.getInputText();
        String preview = inputText.length() <= PREVIEW_LENGTH ? inputText : inputText.substring(0, PREVIEW_LENGTH);
        return new GrammarCheckListItemResponse(
                check.getId(),
                check.getStatus(),
                preview,
                grammarCheckErrorRepository.countByGrammarCheck(check),
                check.getFailureMessage(),
                check.getCreatedAt(),
                check.getCompletedAt());
    }

    private GrammarCheckResponse toResponse(GrammarCheck check, List<GrammarCheckError> errors) {
        return new GrammarCheckResponse(
                check.getId(),
                check.getStatus(),
                check.getInputText(),
                idOf(check.getCourse()),
                idOf(check.getLesson()),
                idOf(check.getQuestion()),
                idOf(check.getEnrollment()),
                check.getFailureMessage(),
                check.getCreatedAt(),
                check.getCompletedAt(),
                errors.stream().map(this::toErrorResponse).toList());
    }

    private GrammarCheckErrorResponse toErrorResponse(GrammarCheckError error) {
        return new GrammarCheckErrorResponse(
                error.getId(),
                error.getSequenceOrder(),
                error.getErrorText(),
                error.getErrorType(),
                error.getSuggestion(),
                error.getExplanation(),
                error.getStartPos(),
                error.getEndPos());
    }

    private Long idOf(Course course) {
        return course == null ? null : course.getId();
    }

    private Long idOf(Lesson lesson) {
        return lesson == null ? null : lesson.getId();
    }

    private Long idOf(Question question) {
        return question == null ? null : question.getId();
    }

    private Long idOf(CourseEnrollment enrollment) {
        return enrollment == null ? null : enrollment.getId();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safeFailureMessage(RuntimeException exception) {
        return isBlank(exception.getMessage())
                ? "AI grammar check is temporarily unavailable. Please try again later."
                : exception.getMessage();
    }
}
