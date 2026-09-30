package com.learne.backend.student.learning.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.service.CourseAccessService;
import com.learne.backend.shared.dashboard.service.LeaderboardReadCacheService;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.dto.SubmitQuizRequest;
import com.learne.backend.student.learning.dto.SubmitQuizResponse;
import com.learne.backend.student.learning.dto.StudentActiveCourseResponse;
import com.learne.backend.student.learning.dto.StudentCourseDetailResponse;
import com.learne.backend.student.learning.dto.StudentCourseSummaryResponse;
import com.learne.backend.student.learning.dto.StudentDashboardResponse;
import com.learne.backend.student.learning.dto.StudentEnrollmentProgressResponse;
import com.learne.backend.student.learning.dto.StudentEnrollmentResponse;
import com.learne.backend.student.learning.dto.StudentEnrollmentSummaryResponse;
import com.learne.backend.student.learning.dto.StudentQuizAnswerResultResponse;
import com.learne.backend.student.learning.dto.StudentQuizAttemptResponse;
import com.learne.backend.student.learning.dto.StudentQuizProgressResponse;
import com.learne.backend.student.learning.dto.StudentQuizQuestionResponse;
import com.learne.backend.student.learning.dto.StudentQuizResponse;
import com.learne.backend.student.learning.dto.StudentRecentActivityResponse;
import com.learne.backend.student.learning.dto.StudentStatsResponse;
import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.entity.LessonProgress;
import com.learne.backend.student.learning.entity.QuestionAnswer;
import com.learne.backend.student.learning.entity.QuizAttempt;
import com.learne.backend.student.learning.enums.EnrollmentStatus;
import com.learne.backend.student.learning.enums.CourseAccessStatus;
import com.learne.backend.student.learning.enums.LessonProgressStatus;
import com.learne.backend.student.learning.enums.QuizAttemptStatus;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.LearningActivityRepository;
import com.learne.backend.student.learning.repository.LessonProgressRepository;
import com.learne.backend.student.learning.repository.QuestionAnswerRepository;
import com.learne.backend.student.learning.repository.QuizAttemptRepository;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StudentLearningService {
    private final CourseRepository courseRepository;
    private final CourseEnrollmentRepository courseEnrollmentRepository;
    private final LessonProgressRepository lessonProgressRepository;
    private final QuestionRepository questionRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final QuestionAnswerRepository questionAnswerRepository;
    private final LearningActivityRepository learningActivityRepository;
    private final StudentQuizGradingService studentQuizGradingService;
    private final StudentStatsService studentStatsService;
    private final LeaderboardReadCacheService leaderboardReadCacheService;
    private final CourseAccessService courseAccessService;
    private final EntityManager entityManager;
    private final TransactionTemplate createEnrollmentTransactionTemplate;

    public StudentLearningService(CourseRepository courseRepository,
                                    CourseEnrollmentRepository courseEnrollmentRepository,
                                      LessonProgressRepository lessonProgressRepository,
                                       QuestionRepository questionRepository,
                                       QuizAttemptRepository quizAttemptRepository,
                                       QuestionAnswerRepository questionAnswerRepository,
                                       LearningActivityRepository learningActivityRepository,
                                       StudentQuizGradingService studentQuizGradingService,
                                       StudentStatsService studentStatsService,
                                       LeaderboardReadCacheService leaderboardReadCacheService,
                                       CourseAccessService courseAccessService,
                                       EntityManager entityManager,
                                       PlatformTransactionManager transactionManager) {
        this.courseRepository = courseRepository;
        this.courseEnrollmentRepository = courseEnrollmentRepository;
        this.lessonProgressRepository = lessonProgressRepository;
        this.questionRepository = questionRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.questionAnswerRepository = questionAnswerRepository;
        this.learningActivityRepository = learningActivityRepository;
        this.studentQuizGradingService = studentQuizGradingService;
        this.studentStatsService = studentStatsService;
        this.leaderboardReadCacheService = leaderboardReadCacheService;
        this.courseAccessService = courseAccessService;
        this.entityManager = entityManager;
        this.createEnrollmentTransactionTemplate = new TransactionTemplate(transactionManager);
        this.createEnrollmentTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public List<StudentCourseSummaryResponse> listCourses(User student, String level, Long teacherId) {
        return courseRepository.findStudentCatalog(ContentStatus.APPROVED, normalize(level), teacherId).stream()
                .map(course -> toCourseSummary(student, course))
                .toList();
    }

    @Transactional
    public StudentEnrollmentResponse enroll(User student, Long courseId) {
        Course course = courseRepository.findById(courseId)
                .filter(found -> found.getStatus() == ContentStatus.APPROVED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
        if (!courseAccessService.canEnroll(student, course)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Course access is required.");
        }

        CourseEnrollment enrollment = courseEnrollmentRepository.findByUserAndCourse(student, course)
                .orElseGet(() -> findOrCreateEnrollment(student, course));
        return StudentEnrollmentResponse.from(enrollment);
    }

    @Transactional
    public StudentStatsResponse getStats(User student) {
        return studentStatsService.getStats(student);
    }

    @Transactional(readOnly = true)
    public StudentDashboardResponse getDashboard(User student) {
        StudentStatsResponse stats = getStats(student);
        long totalEnrollments = courseEnrollmentRepository.countByUser(student);
        long activeEnrollments = courseEnrollmentRepository.countByUserAndStatusIn(student, List.of(EnrollmentStatus.ACTIVE));
        long completedLessons = lessonProgressRepository.countByUserAndStatus(student, LessonProgressStatus.COMPLETED);

        return new StudentDashboardResponse(
                stats,
                new StudentEnrollmentSummaryResponse(totalEnrollments, activeEnrollments, completedLessons),
                courseEnrollmentRepository.findRecentDashboardCoursesByUser(student, PageRequest.of(0, 4)).stream()
                        .map(StudentActiveCourseResponse::from)
                        .toList(),
                learningActivityRepository.findRecentByUser(student, PageRequest.of(0, 5)).stream()
                        .map(activity -> new StudentRecentActivityResponse(
                                activity.getId(),
                                activity.getSourceType().name(),
                                activity.getSourceType().name(),
                                activity.getCreatedAt()))
                        .toList(),
                leaderboardReadCacheService.topStudents(5)
        );
    }

    @Transactional(readOnly = true)
    public StudentQuizResponse getLessonQuiz(User student, Long enrollmentId, Long lessonId) {
        CourseEnrollment enrollment = findEnrollment(student, enrollmentId);
        ensureCourseAccess(student, enrollment);
        LessonProgress progress = findLessonProgress(enrollment, lessonId);
        ensureLessonStillApproved(progress);
        if (progress.getStatus() == LessonProgressStatus.LOCKED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Lesson is locked.");
        }

        Lesson lesson = progress.getLesson();
        List<StudentQuizQuestionResponse> questions = questionRepository.findByLessonAndStatusForStudentQuiz(lesson, ContentStatus.APPROVED).stream()
                .map(StudentQuizQuestionResponse::from)
                .toList();
        Integer bestScorePercent = quizAttemptRepository.findBestScorePercent(student, lesson);
        boolean alreadyPassed = quizAttemptRepository.existsByUserAndLessonAndPassedTrue(student, lesson);
        return StudentQuizResponse.from(enrollment.getId(), lesson, bestScorePercent, alreadyPassed, questions);
    }

    @Transactional
    public SubmitQuizResponse submitLessonQuiz(User student, Long enrollmentId, Long lessonId, SubmitQuizRequest request) {
        CourseEnrollment enrollment = findEnrollment(student, enrollmentId);
        ensureCourseAccess(student, enrollment);
        LessonProgress progress = findLessonProgress(enrollment, lessonId);
        ensureLessonStillApproved(progress);
        if (progress.getStatus() == LessonProgressStatus.LOCKED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Lesson is locked.");
        }

        Lesson lesson = progress.getLesson();
        List<Question> questions = questionRepository.findByLessonAndStatusForStudentQuiz(lesson, ContentStatus.APPROVED);
        StudentQuizGradingResult gradingResult = studentQuizGradingService.grade(lesson, questions, request);
        boolean alreadyPassed = quizAttemptRepository.existsByUserAndLessonAndPassedTrue(student, lesson);
        int questionXp = gradingResult.answers().stream()
                .filter(StudentQuestionGradingResult::correct)
                .mapToInt(answerResult -> answerResult.question().getXpReward())
                .sum();
        int potentialAttemptXp = gradingResult.passed() ? lesson.getXpReward() + questionXp : 0;
        boolean shouldAttemptAwardXp = gradingResult.passed() && !alreadyPassed;
        if (gradingResult.passed() && !alreadyPassed) {
            // Stats creation recovery clears the persistence context, so do it before pending quiz changes exist.
            studentStatsService.getOrCreateStats(student);
        }

        QuizAttempt attempt = new QuizAttempt();
        attempt.setUser(student);
        attempt.setCourseEnrollment(enrollment);
        attempt.setLesson(lesson);
        attempt.setStatus(QuizAttemptStatus.SUBMITTED);
        attempt.setScorePercent(gradingResult.scorePercent());
        attempt.setPassed(gradingResult.passed());
        attempt.setEarnedXp(0);
        attempt.setSubmittedAt(Instant.now());
        for (StudentQuestionGradingResult answerResult : gradingResult.answers()) {
            QuestionAnswer answer = new QuestionAnswer();
            answer.setQuizAttempt(attempt);
            answer.setQuestion(answerResult.question());
            answer.setAnswerText(answerResult.answer() == null ? null : answerResult.answer().answerText());
            answer.setSelectedOption(answerResult.selectedOption());
            answer.setCorrect(answerResult.correct());
            answer.setScorePercent(answerResult.scorePercent());
            answer.setEarnedXp(0);
            answer.setGradingMethod(answerResult.gradingMethod());
            answer.setFeedback(answerResult.feedback());
            attempt.getAnswers().add(answer);
        }
        QuizAttempt savedAttempt = quizAttemptRepository.save(attempt);

        Long unlockedNextLessonId = null;
        StudentStatsResponse stats;
        boolean xpAwarded = false;

        if (gradingResult.passed()) {
            if (progress.getStatus() != LessonProgressStatus.COMPLETED) {
                Instant now = Instant.now();
                if (progress.getStartedAt() == null) {
                    progress.setStartedAt(now);
                }
                if (progress.getCompletedAt() == null) {
                    progress.setCompletedAt(now);
                }
                progress.setProgressPercent(100);
                progress.setStatus(LessonProgressStatus.COMPLETED);
                lessonProgressRepository.save(progress);
                recomputeEnrollmentProgress(enrollment);
                unlockedNextLessonId = unlockNextLessonAndReturnId(enrollment);
            }
            if (shouldAttemptAwardXp) {
                StudentStatsAwardResult awardResult = studentStatsService.awardLessonPass(student, lesson.getId(), potentialAttemptXp);
                stats = awardResult.stats();
                xpAwarded = awardResult.xpAwarded();
                if (xpAwarded) {
                    savedAttempt.setEarnedXp(potentialAttemptXp);
                    savedAttempt.getAnswers().forEach(answer -> {
                        if (answer.isCorrect()) {
                            answer.setEarnedXp(answer.getQuestion().getXpReward());
                        }
                    });
                    savedAttempt = quizAttemptRepository.saveAndFlush(savedAttempt);
                }
            } else {
                stats = studentStatsService.getStats(student);
            }
        } else {
            if (progress.getStatus() == LessonProgressStatus.UNLOCKED) {
                progress.setStatus(LessonProgressStatus.IN_PROGRESS);
                if (progress.getStartedAt() == null) {
                    progress.setStartedAt(Instant.now());
                }
                lessonProgressRepository.save(progress);
            }
            stats = studentStatsService.getStats(student);
        }

        return new SubmitQuizResponse(
                savedAttempt.getId(),
                enrollment.getId(),
                lesson.getId(),
                gradingResult.scorePercent(),
                gradingResult.passed(),
                savedAttempt.getEarnedXp(),
                xpAwarded,
                StudentQuizProgressResponse.from(progress, enrollment, unlockedNextLessonId),
                stats,
                savedAttempt.getAnswers().stream()
                        .map(this::toAnswerResponse)
                        .toList()
        );
    }

    @Transactional(readOnly = true)
    public StudentQuizAttemptResponse getQuizAttempt(User student, Long attemptId) {
        QuizAttempt attempt = quizAttemptRepository.findByIdAndUser(attemptId, student)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Quiz attempt not found."));
        CourseEnrollment enrollment = attempt.getCourseEnrollment();
        ensureCourseAccess(student, enrollment);
        LessonProgress progress = findLessonProgress(enrollment, attempt.getLesson().getId());
        List<QuestionAnswer> answers = questionAnswerRepository.findByQuizAttemptOrderByQuestionOrderIndexAscQuestionIdAsc(attempt);

        return new StudentQuizAttemptResponse(
                attempt.getId(),
                enrollment.getId(),
                attempt.getLesson().getId(),
                attempt.getScorePercent(),
                attempt.isPassed(),
                attempt.getEarnedXp(),
                StudentQuizProgressResponse.from(progress, enrollment, null),
                studentStatsService.getStats(student),
                answers.stream()
                        .map(this::toAnswerResponse)
                        .toList()
        );
    }

    @Transactional(readOnly = true)
    public List<StudentEnrollmentResponse> listEnrollments(User student) {
        return courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student).stream()
                .map(StudentEnrollmentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public StudentCourseDetailResponse getCourse(User student, Long courseId) {
        Course course = courseRepository.findStudentDetailByIdAndStatus(courseId, ContentStatus.APPROVED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
        CourseEnrollment enrollment = courseEnrollmentRepository.findByUserAndCourse(student, course).orElse(null);
        CourseAccessStatus accessStatus = courseAccessService.accessStatus(student, course);
        return StudentCourseDetailResponse.from(course, enrollment, accessStatus, accessReason(accessStatus), accessStatus != CourseAccessStatus.LOCKED);
    }

    @Transactional(readOnly = true)
    public StudentEnrollmentProgressResponse getEnrollmentProgress(User student, Long enrollmentId) {
        CourseEnrollment enrollment = findEnrollment(student, enrollmentId);
        ensureCourseAccess(student, enrollment);
        return StudentEnrollmentProgressResponse.from(enrollment);
    }

    @Transactional
    public StudentEnrollmentProgressResponse startLesson(User student, Long enrollmentId, Long lessonId) {
        CourseEnrollment enrollment = findEnrollment(student, enrollmentId);
        ensureCourseAccess(student, enrollment);
        LessonProgress progress = findLessonProgress(enrollment, lessonId);
        ensureLessonStillApproved(progress);
        if (progress.getStatus() == LessonProgressStatus.LOCKED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Lesson is locked.");
        }
        if (progress.getStatus() == LessonProgressStatus.UNLOCKED) {
            progress.setStatus(LessonProgressStatus.IN_PROGRESS);
            progress.setStartedAt(Instant.now());
            lessonProgressRepository.save(progress);
        }
        return StudentEnrollmentProgressResponse.from(enrollment);
    }

    @Transactional
    public StudentEnrollmentProgressResponse completeLesson(User student, Long enrollmentId, Long lessonId) {
        CourseEnrollment enrollment = findEnrollment(student, enrollmentId);
        ensureCourseAccess(student, enrollment);
        LessonProgress progress = findLessonProgress(enrollment, lessonId);
        ensureLessonStillApproved(progress);
        if (progress.getStatus() == LessonProgressStatus.LOCKED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Lesson is locked.");
        }
        if (hasApprovedGradableQuestions(progress.getLesson())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Lesson quiz must be passed.");
        }
        if (progress.getStatus() != LessonProgressStatus.COMPLETED) {
            Instant now = Instant.now();
            if (progress.getStartedAt() == null) {
                progress.setStartedAt(now);
            }
            progress.setCompletedAt(now);
            progress.setProgressPercent(100);
            progress.setStatus(LessonProgressStatus.COMPLETED);
            lessonProgressRepository.save(progress);
            recomputeEnrollmentProgress(enrollment);
            unlockNextLessonAndReturnId(enrollment);
        }
        return StudentEnrollmentProgressResponse.from(enrollment);
    }

    private CourseEnrollment findOrCreateEnrollment(User student, Course course) {
        try {
            return createEnrollmentTransactionTemplate.execute(status -> createEnrollment(student, course));
        } catch (DataIntegrityViolationException ex) {
            entityManager.clear();
            return courseEnrollmentRepository.findByUserAndCourse(student, course)
                    .orElseThrow(() -> ex);
        }
    }

    public CourseEnrollment createEnrollment(User student, Course course) {
        CourseEnrollment enrollment = new CourseEnrollment();
        enrollment.setUser(student);
        enrollment.setCourse(course);
        enrollment.setStatus(EnrollmentStatus.ACTIVE);
        enrollment.setProgressPercent(0);

        List<Lesson> lessons = orderedApprovedLessons(course);
        for (int index = 0; index < lessons.size(); index++) {
            LessonProgress progress = new LessonProgress();
            progress.setUser(student);
            progress.setCourseEnrollment(enrollment);
            progress.setLesson(lessons.get(index));
            progress.setStatus(index == 0 ? LessonProgressStatus.UNLOCKED : LessonProgressStatus.LOCKED);
            progress.setProgressPercent(0);
            enrollment.getLessonProgress().add(progress);
        }

        return courseEnrollmentRepository.saveAndFlush(enrollment);
    }

    public List<Lesson> orderedApprovedLessons(Course course) {
        return course.getTopics().stream()
                .sorted(Comparator.comparingInt(Topic::getOrderIndex).thenComparing(Topic::getId))
                .flatMap(topic -> topic.getLessons().stream()
                        .filter(lesson -> lesson.getStatus() == ContentStatus.APPROVED)
                        .sorted(Comparator.comparingInt(Lesson::getOrderIndex).thenComparing(Lesson::getId)))
                .toList();
    }

    private CourseEnrollment findEnrollment(User student, Long enrollmentId) {
        return courseEnrollmentRepository.findByIdAndUser(enrollmentId, student)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Enrollment not found."));
    }

    private void ensureCourseAccess(User student, CourseEnrollment enrollment) {
        if (!courseAccessService.canEnroll(student, enrollment.getCourse())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Course access is required.");
        }
    }

    private LessonProgress findLessonProgress(CourseEnrollment enrollment, Long lessonId) {
        return enrollment.getLessonProgress().stream()
                .filter(progress -> progress.getLesson().getId().equals(lessonId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Lesson not found."));
    }

    private void ensureLessonStillApproved(LessonProgress progress) {
        if (progress.getLesson().getStatus() != ContentStatus.APPROVED
                || progress.getLesson().getTopic().getCourse().getStatus() != ContentStatus.APPROVED) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Lesson not found.");
        }
    }

    private boolean hasApprovedGradableQuestions(Lesson lesson) {
        return questionRepository.findByLessonAndStatusForStudentQuiz(lesson, ContentStatus.APPROVED).stream()
                .anyMatch(question -> question.getType() != QuestionType.PRONUNCIATION);
    }

    private void recomputeEnrollmentProgress(CourseEnrollment enrollment) {
        long total = enrollment.getLessonProgress().size();
        long completed = enrollment.getLessonProgress().stream()
                .filter(progress -> progress.getStatus() == LessonProgressStatus.COMPLETED)
                .count();
        int percent = total == 0 ? 0 : (int) Math.round((completed * 100.0) / total);
        enrollment.setProgressPercent(percent);
        if (total > 0 && completed == total) {
            enrollment.setStatus(EnrollmentStatus.COMPLETED);
            if (enrollment.getCompletedAt() == null) {
                enrollment.setCompletedAt(Instant.now());
            }
        }
        courseEnrollmentRepository.save(enrollment);
    }

    private Long unlockNextLessonAndReturnId(CourseEnrollment enrollment) {
        List<LessonProgress> ordered = orderedProgress(enrollment);
        for (int index = 0; index < ordered.size(); index++) {
            LessonProgress current = ordered.get(index);
            if (current.getStatus() == LessonProgressStatus.COMPLETED && index + 1 < ordered.size()) {
                LessonProgress next = ordered.get(index + 1);
                if (next.getStatus() == LessonProgressStatus.LOCKED) {
                    next.setStatus(LessonProgressStatus.UNLOCKED);
                    lessonProgressRepository.save(next);
                    return next.getLesson().getId();
                }
            }
        }
        return null;
    }

    private List<LessonProgress> orderedProgress(CourseEnrollment enrollment) {
        return enrollment.getLessonProgress().stream()
                .sorted(Comparator
                        .comparingInt((LessonProgress progress) -> progress.getLesson().getTopic().getOrderIndex())
                        .thenComparing(progress -> progress.getLesson().getTopic().getId())
                        .thenComparingInt(progress -> progress.getLesson().getOrderIndex())
                        .thenComparing(progress -> progress.getLesson().getId()))
                .toList();
    }

    private StudentQuizAnswerResultResponse toAnswerResponse(StudentQuestionGradingResult result, boolean xpAwarded) {
        Long selectedOptionId = result.selectedOption() == null ? null : result.selectedOption().getId();
        String selectedOptionText = result.selectedOption() == null ? null : result.selectedOption().getOptionText();
        int earnedXp = xpAwarded && result.correct() ? result.question().getXpReward() : 0;
        return new StudentQuizAnswerResultResponse(
                result.question().getId(),
                result.question().getType().name(),
                result.question().getQuestionText(),
                result.answer() == null ? null : result.answer().answerText(),
                selectedOptionId,
                selectedOptionText,
                result.correct(),
                result.scorePercent(),
                earnedXp,
                result.gradingMethod().name(),
                result.feedback(),
                result.correctAnswer(),
                result.correctOptionId(),
                correctOptionText(result.question(), result.correctOptionId()),
                result.question().getExplanation()
        );
    }

    private StudentQuizAnswerResultResponse toAnswerResponse(QuestionAnswer answer) {
        Question question = answer.getQuestion();
        QuestionOption selectedOption = answer.getSelectedOption();
        return new StudentQuizAnswerResultResponse(
                question.getId(),
                question.getType().name(),
                question.getQuestionText(),
                answer.getAnswerText(),
                selectedOption == null ? null : selectedOption.getId(),
                selectedOption == null ? null : selectedOption.getOptionText(),
                answer.isCorrect(),
                answer.getScorePercent(),
                answer.getEarnedXp(),
                answer.getGradingMethod().name(),
                answer.getFeedback(),
                usesOptionGrading(question) ? null : question.getCorrectAnswer(),
                usesOptionGrading(question) ? correctOptionId(question) : null,
                usesOptionGrading(question) ? correctOptionText(question) : null,
                question.getExplanation()
        );
    }

    private boolean usesOptionGrading(Question question) {
        return question.getType() == QuestionType.MULTIPLE_CHOICE
                || (question.getType() == QuestionType.TRUE_FALSE && question.getOptions().stream().anyMatch(QuestionOption::isActive));
    }

    private Long correctOptionId(Question question) {
        return question.getOptions().stream()
                .filter(QuestionOption::isActive)
                .filter(QuestionOption::isCorrect)
                .findFirst()
                .map(QuestionOption::getId)
                .orElse(null);
    }

    private String correctOptionText(Question question) {
        return question.getOptions().stream()
                .filter(QuestionOption::isActive)
                .filter(QuestionOption::isCorrect)
                .findFirst()
                .map(QuestionOption::getOptionText)
                .orElse(null);
    }

    private String correctOptionText(Question question, Long correctOptionId) {
        if (correctOptionId == null) {
            return null;
        }
        return question.getOptions().stream()
                .filter(QuestionOption::isActive)
                .filter(option -> option.getId().equals(correctOptionId))
                .findFirst()
                .map(QuestionOption::getOptionText)
                .orElse(null);
    }

    private StudentCourseSummaryResponse toCourseSummary(User student, Course course) {
        List<Lesson> approvedLessons = orderedApprovedLessons(course);
        CourseAccessStatus accessStatus = courseAccessService.accessStatus(student, course);
        long approvedTopicCount = course.getTopics().stream()
                .filter(topic -> topic.getLessons().stream()
                        .anyMatch(lesson -> lesson.getStatus() == ContentStatus.APPROVED))
                .count();
        long approvedQuestionCount = approvedLessons.stream()
                .flatMap(lesson -> lesson.getQuestions().stream())
                .filter(question -> question.getStatus() == ContentStatus.APPROVED)
                .count();

        return new StudentCourseSummaryResponse(
                course.getId(),
                course.getName(),
                course.getDescription(),
                course.getType(),
                course.getLevel(),
                course.getTeacher().getId(),
                course.getTeacher().getFullName(),
                course.getTeacher().getEmail(),
                course.getTeacher().getAvatarUrl(),
                approvedTopicCount,
                approvedLessons.size(),
                approvedQuestionCount,
                course.getPublishedAt(),
                course.getPricingType(),
                course.getPriceAmountVnd(),
                accessStatus,
                accessReason(accessStatus),
                courseEnrollmentRepository.existsByUserAndCourse(student, course)
        );
    }

    private String accessReason(CourseAccessStatus accessStatus) {
        return switch (accessStatus) {
            case FREE -> "Course is free.";
            case OWNED -> "Course is unlocked.";
            case LOCKED -> "Purchase is required.";
        };
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
