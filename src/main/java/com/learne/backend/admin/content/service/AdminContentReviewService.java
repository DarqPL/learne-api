package com.learne.backend.admin.content.service;

import com.learne.backend.admin.content.dto.AdminReviewCourseDetailResponse;
import com.learne.backend.admin.content.dto.AdminReviewCourseSummaryResponse;
import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;
import com.learne.backend.teacher.content.entity.QuestionOptionRevision;
import com.learne.backend.teacher.content.entity.QuestionRevision;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CoursePriceStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminContentReviewService {
    private static final String REVIEW_STATUS_MESSAGE = "Only pending review courses can be approved or rejected.";
    private static final String STALE_REVISION_MESSAGE = "Nội dung này không còn là phiên bản mới nhất.";

    private final CourseRepository courseRepository;
    private final CourseRevisionRepository courseRevisionRepository;
    private final EntityManager entityManager;

    public AdminContentReviewService(CourseRepository courseRepository,
                                     CourseRevisionRepository courseRevisionRepository,
                                     EntityManager entityManager) {
        this.courseRepository = courseRepository;
        this.courseRevisionRepository = courseRevisionRepository;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<AdminReviewCourseSummaryResponse> listPendingCourses() {
        return courseRevisionRepository.findLatestByStatusOrderByUpdatedAtAsc(CourseRevisionStatus.PENDING_REVIEW).stream()
                .map(AdminReviewCourseSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminReviewCourseDetailResponse getCourse(Long courseId) {
        return AdminReviewCourseDetailResponse.from(findLatestPendingRevisionForCourse(courseId));
    }

    @Transactional
    public AdminReviewCourseDetailResponse approveCourse(User admin, Long courseId) {
        CourseRevision revision = findCoursePathReviewRevision(courseId);
        return approveResolvedRevision(admin, revision);
    }

    @Transactional
    public AdminReviewCourseDetailResponse approveRevision(User admin, Long revisionId) {
        CourseRevision revision = findReviewRevision(revisionId);
        return approveResolvedRevision(admin, revision);
    }

    private AdminReviewCourseDetailResponse approveResolvedRevision(User admin, CourseRevision revision) {
        CourseRevision latestRevision = latestLockedRevision(revision.getCourse().getId());
        ensureLatestPendingRevision(revision, latestRevision);
        if (!hasMinimumReviewContent(revision)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Course must have at least one topic, lesson, and question before approval.");
        }
        if (revision.getPricingType() == CoursePricingType.PAID && "A1".equalsIgnoreCase(revision.getLevel())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A1 courses must be free.");
        }
        if (revision.getPricingType() == CoursePricingType.PAID
                && (revision.getProposedPriceAmountVnd() == null || revision.getProposedPriceAmountVnd() <= 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Paid courses require a positive proposed price.");
        }

        Instant now = Instant.now();
        revision.setStatus(CourseRevisionStatus.APPROVED);
        revision.setReviewedBy(admin);
        revision.setReviewedAt(now);
        revision.setPublishedAt(now);
        revision.setRejectionReason(null);

        Course course = revision.getCourse();
        MaterializedSources materializedSources = publishRevisionToLive(course, revision, admin, now);
        Course savedCourse = courseRepository.saveAndFlush(course);
        linkMaterializedSources(materializedSources);

        return AdminReviewCourseDetailResponse.from(savedCourse, revision);
    }

    @Transactional
    public AdminReviewCourseDetailResponse rejectCourse(User admin, Long courseId, String reason) {
        CourseRevision revision = findCoursePathReviewRevision(courseId);
        return rejectResolvedRevision(admin, revision, reason);
    }

    @Transactional
    public AdminReviewCourseDetailResponse rejectRevision(User admin, Long revisionId, String reason) {
        CourseRevision revision = findReviewRevision(revisionId);
        return rejectResolvedRevision(admin, revision, reason);
    }

    private AdminReviewCourseDetailResponse rejectResolvedRevision(User admin, CourseRevision revision, String reason) {
        CourseRevision latestRevision = latestLockedRevision(revision.getCourse().getId());
        ensureLatestPendingRevision(revision, latestRevision);

        Instant now = Instant.now();
        revision.setStatus(CourseRevisionStatus.REJECTED);
        revision.setReviewedBy(admin);
        revision.setReviewedAt(now);
        revision.setPublishedAt(null);
        revision.setRejectionReason(reason);

        Course course = revision.getCourse();
        if (course.getPublishedAt() == null) {
            course.setStatus(ContentStatus.REJECTED);
            if (revision.getPricingType() == CoursePricingType.PAID) {
                course.setPriceStatus(CoursePriceStatus.REJECTED);
            }
            course.setReviewedBy(admin);
            course.setReviewedAt(now);
            course.setPublishedAt(null);
            course.setRejectionReason(reason);
            course.getTopics().forEach(topic -> topic.getLessons().forEach(lesson -> {
                lesson.setStatus(ContentStatus.REJECTED);
                lesson.setReviewedBy(admin);
                lesson.setReviewedAt(now);
                lesson.setPublishedAt(null);
                lesson.setRejectionReason(null);

                lesson.getQuestions().forEach(question -> {
                    question.setStatus(ContentStatus.REJECTED);
                    question.setReviewedBy(admin);
                    question.setReviewedAt(now);
                    question.setRejectionReason(null);
                });
            }));
        }

        courseRevisionRepository.save(revision);
        return AdminReviewCourseDetailResponse.from(courseRepository.save(course), revision);
    }

    private MaterializedSources publishRevisionToLive(Course course, CourseRevision revision, User admin, Instant now) {
        MaterializedSources materializedSources = new MaterializedSources(
                new IdentityHashMap<>(), new IdentityHashMap<>(), new IdentityHashMap<>(), new IdentityHashMap<>());
        course.setName(revision.getName());
        course.setDescription(revision.getDescription());
        course.setType(revision.getType());
        course.setLevel(revision.getLevel());
        course.setPricingType(revision.getPricingType());
        if (revision.getPricingType() == CoursePricingType.PAID) {
            course.setPriceAmountVnd(revision.getProposedPriceAmountVnd());
            course.setProposedPriceAmountVnd(revision.getProposedPriceAmountVnd());
            course.setPriceStatus(CoursePriceStatus.APPROVED);
        } else {
            course.setPriceAmountVnd(null);
            course.setProposedPriceAmountVnd(null);
            course.setPriceStatus(CoursePriceStatus.NONE);
        }
        course.setOrderIndex(revision.getOrderIndex());
        course.setStatus(ContentStatus.APPROVED);
        course.setReviewedBy(admin);
        course.setReviewedAt(now);
        course.setPublishedAt(now);
        course.setRejectionReason(null);

        Set<Topic> materializedTopics = new HashSet<>();
        Set<Lesson> materializedLessons = new HashSet<>();
        Set<Question> materializedQuestions = new HashSet<>();
        Set<QuestionOption> materializedOptions = new HashSet<>();

        revision.getTopics().forEach(topicRevision -> {
            Topic topic = resolveLiveTopic(course, topicRevision);
            topic.setCourse(course);
            topic.setName(topicRevision.getName());
            topic.setDescription(topicRevision.getDescription());
            topic.setOrderIndex(topicRevision.getOrderIndex());
            materializedSources.topics().put(topicRevision, topic);
            if (!course.getTopics().contains(topic)) {
                course.getTopics().add(topic);
            }
            persistIfNew(topic);
            materializedTopics.add(topic);

            topicRevision.getLessons().forEach(lessonRevision -> {
                Lesson lesson = resolveLiveLesson(course, lessonRevision);
                materializeLiveLesson(topic, lesson, lessonRevision, admin, now);
                materializedSources.lessons().put(lessonRevision, lesson);
                if (!topic.getLessons().contains(lesson)) {
                    topic.getLessons().add(lesson);
                }
                persistIfNew(lesson);
                materializedLessons.add(lesson);

                lessonRevision.getQuestions().forEach(questionRevision -> {
                    Question question = resolveLiveQuestion(course, questionRevision);
                    materializeLiveQuestion(lesson, question, questionRevision, admin, now);
                    materializedSources.questions().put(questionRevision, question);
                    if (!lesson.getQuestions().contains(question)) {
                        lesson.getQuestions().add(question);
                    }
                    persistIfNew(question);
                    materializedQuestions.add(question);

                    questionRevision.getOptions().forEach(optionRevision -> {
                        QuestionOption option = resolveLiveOption(course, optionRevision);
                        materializeLiveOption(question, option, optionRevision);
                        materializedSources.options().put(optionRevision, option);
                        if (!question.getOptions().contains(option)) {
                            question.getOptions().add(option);
                        }
                        persistIfNew(option);
                        materializedOptions.add(option);
                    });
                });
            });
        });

        course.getTopics().forEach(topic -> {
            boolean topicMaterialized = materializedTopics.contains(topic);
            topic.getLessons().forEach(lesson -> {
                if (!topicMaterialized || !materializedLessons.contains(lesson)) {
                    rejectLiveLesson(lesson, admin, now);
                } else {
                    lesson.getQuestions().forEach(question -> {
                        if (!materializedQuestions.contains(question)) {
                            rejectLiveQuestion(question, admin, now);
                        } else {
                            question.getOptions().forEach(option -> {
                                if (!materializedOptions.contains(option)) {
                                    option.setActive(false);
                                }
                            });
                        }
                    });
                }
            });
        });
        return materializedSources;
    }

    private void linkMaterializedSources(MaterializedSources materializedSources) {
        materializedSources.topics().forEach(TopicRevision::setSourceTopic);
        materializedSources.lessons().forEach(LessonRevision::setSourceLesson);
        materializedSources.questions().forEach(QuestionRevision::setSourceQuestion);
        materializedSources.options().forEach(QuestionOptionRevision::setSourceOption);
    }

    private void persistIfNew(Topic topic) {
        if (topic.getId() == null) {
            entityManager.persist(topic);
        }
    }

    private void persistIfNew(Lesson lesson) {
        if (lesson.getId() == null) {
            entityManager.persist(lesson);
        }
    }

    private void persistIfNew(Question question) {
        if (question.getId() == null) {
            entityManager.persist(question);
        }
    }

    private void persistIfNew(QuestionOption option) {
        if (option.getId() == null) {
            entityManager.persist(option);
        }
    }

    private record MaterializedSources(Map<TopicRevision, Topic> topics,
                                       Map<LessonRevision, Lesson> lessons,
                                       Map<QuestionRevision, Question> questions,
                                       Map<QuestionOptionRevision, QuestionOption> options) {
    }

    private Topic resolveLiveTopic(Course course, TopicRevision topicRevision) {
        Topic sourceTopic = topicRevision.getSourceTopic();
        if (sourceTopic != null && sourceTopic.getCourse().getId().equals(course.getId())) {
            return sourceTopic;
        }
        return new Topic();
    }

    private Lesson resolveLiveLesson(Course course, LessonRevision lessonRevision) {
        Lesson sourceLesson = lessonRevision.getSourceLesson();
        if (sourceLesson != null && sourceLesson.getTopic().getCourse().getId().equals(course.getId())) {
            return sourceLesson;
        }
        return new Lesson();
    }

    private Question resolveLiveQuestion(Course course, QuestionRevision questionRevision) {
        Question sourceQuestion = questionRevision.getSourceQuestion();
        if (sourceQuestion != null && sourceQuestion.getLesson().getTopic().getCourse().getId().equals(course.getId())) {
            return sourceQuestion;
        }
        return new Question();
    }

    private QuestionOption resolveLiveOption(Course course, QuestionOptionRevision optionRevision) {
        QuestionOption sourceOption = optionRevision.getSourceOption();
        if (sourceOption != null
                && sourceOption.getQuestion().getLesson().getTopic().getCourse().getId().equals(course.getId())) {
            return sourceOption;
        }
        return new QuestionOption();
    }

    private void materializeLiveLesson(Topic topic, Lesson lesson, LessonRevision lessonRevision, User admin, Instant now) {
        lesson.setTopic(topic);
        lesson.setTeacher(lessonRevision.getTeacher());
        lesson.setTitle(lessonRevision.getTitle());
        lesson.setDescription(lessonRevision.getDescription());
        lesson.setContent(lessonRevision.getContent());
        lesson.setAudioUrl(lessonRevision.getAudioUrl());
        lesson.setAudioPublicId(lessonRevision.getAudioPublicId());
        lesson.setSkillType(lessonRevision.getSkillType());
        lesson.setOrderIndex(lessonRevision.getOrderIndex());
        lesson.setXpReward(lessonRevision.getXpReward());
        lesson.setPlacementTest(lessonRevision.isPlacementTest());
        lesson.setStatus(ContentStatus.APPROVED);
        lesson.setReviewedBy(admin);
        lesson.setReviewedAt(now);
        lesson.setPublishedAt(now);
        lesson.setRejectionReason(null);
    }

    private void materializeLiveQuestion(Lesson lesson, Question question, QuestionRevision questionRevision,
                                         User admin, Instant now) {
        question.setLesson(lesson);
        question.setTeacher(questionRevision.getTeacher());
        question.setType(questionRevision.getType());
        question.setQuestionText(questionRevision.getQuestionText());
        question.setExplanation(questionRevision.getExplanation());
        question.setCorrectAnswer(questionRevision.getCorrectAnswer());
        question.setKnowledgePoint(questionRevision.getKnowledgePoint());
        question.setOrderIndex(questionRevision.getOrderIndex());
        question.setDifficulty(questionRevision.getDifficulty());
        question.setXpReward(questionRevision.getXpReward());
        question.setStatus(ContentStatus.APPROVED);
        question.setReviewedBy(admin);
        question.setReviewedAt(now);
        question.setRejectionReason(null);
    }

    private void materializeLiveOption(Question question, QuestionOption option, QuestionOptionRevision optionRevision) {
        option.setQuestion(question);
        option.setOptionText(optionRevision.getOptionText());
        option.setCorrect(optionRevision.isCorrect());
        option.setOrderIndex(optionRevision.getOrderIndex());
        option.setActive(true);
    }

    private void rejectLiveLesson(Lesson lesson, User admin, Instant now) {
        lesson.setStatus(ContentStatus.REJECTED);
        lesson.setReviewedBy(admin);
        lesson.setReviewedAt(now);
        lesson.setPublishedAt(null);
        lesson.setRejectionReason(null);
        lesson.getQuestions().forEach(question -> rejectLiveQuestion(question, admin, now));
    }

    private void rejectLiveQuestion(Question question, User admin, Instant now) {
        question.setStatus(ContentStatus.REJECTED);
        question.setReviewedBy(admin);
        question.setReviewedAt(now);
        question.setRejectionReason(null);
    }

    private Course findReviewCourse(Long courseId) {
        return courseRepository.findReviewDetailById(courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
    }

    private CourseRevision findReviewRevision(Long reviewId) {
        return courseRevisionRepository.findLockedById(reviewId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
    }

    private CourseRevision findCoursePathReviewRevision(Long courseId) {
        return courseRevisionRepository.findLockedTopByCourseIdAndStatusOrderByRevisionNumberDesc(
                        courseId, CourseRevisionStatus.PENDING_REVIEW)
                .or(() -> courseRevisionRepository.findTopByCourseIdOrderByRevisionNumberDesc(courseId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
    }

    private CourseRevision findLatestPendingRevisionForCourse(Long courseId) {
        return courseRevisionRepository.findTopByCourseIdAndStatusOrderByRevisionNumberDesc(
                        courseId, CourseRevisionStatus.PENDING_REVIEW)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
    }

    private CourseRevision latestLockedRevision(Long courseId) {
        return courseRevisionRepository.findTopByCourseIdOrderByRevisionNumberDesc(courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
    }

    private void ensureLatestPendingRevision(CourseRevision revision, CourseRevision latestRevision) {
        if (!latestRevision.getId().equals(revision.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, STALE_REVISION_MESSAGE);
        }
        if (revision.getStatus() != CourseRevisionStatus.PENDING_REVIEW) {
            throw new ApiException(HttpStatus.BAD_REQUEST, REVIEW_STATUS_MESSAGE);
        }
    }

    private void ensurePendingReview(Course course) {
        if (course.getStatus() != ContentStatus.PENDING_REVIEW) {
            throw new ApiException(HttpStatus.BAD_REQUEST, REVIEW_STATUS_MESSAGE);
        }
    }

    private boolean hasMinimumReviewContent(Course course) {
        return course.getTopics().stream()
                .anyMatch(topic -> topic.getLessons().stream()
                        .anyMatch(lesson -> !lesson.getQuestions().isEmpty()));
    }

    private boolean hasMinimumReviewContent(CourseRevision revision) {
        return revision.getTopics().stream()
                .anyMatch(topic -> topic.getLessons().stream()
                        .anyMatch(lesson -> !lesson.getQuestions().isEmpty()));
    }
}
