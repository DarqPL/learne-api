package com.learne.backend.teacher.content.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.dto.CourseRequest;
import com.learne.backend.teacher.content.dto.CourseResponse;
import com.learne.backend.teacher.content.dto.LessonRequest;
import com.learne.backend.teacher.content.dto.LessonResponse;
import com.learne.backend.teacher.content.dto.QuestionOptionRequest;
import com.learne.backend.teacher.content.dto.QuestionRequest;
import com.learne.backend.teacher.content.dto.QuestionResponse;
import com.learne.backend.teacher.content.dto.TopicRequest;
import com.learne.backend.teacher.content.dto.TopicResponse;
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
import com.learne.backend.teacher.content.enums.CoursePriceStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherContentService {
    private final CourseRepository courseRepository;
    private final CourseRevisionRepository courseRevisionRepository;
    private final TopicRepository topicRepository;
    private final LessonRepository lessonRepository;
    private final QuestionRepository questionRepository;

    public TeacherContentService(CourseRepository courseRepository,
                          CourseRevisionRepository courseRevisionRepository,
                           TopicRepository topicRepository,
                           LessonRepository lessonRepository,
                           QuestionRepository questionRepository) {
        this.courseRepository = courseRepository;
        this.courseRevisionRepository = courseRevisionRepository;
        this.topicRepository = topicRepository;
        this.lessonRepository = lessonRepository;
        this.questionRepository = questionRepository;
    }

    @Transactional(readOnly = true)
    public List<CourseResponse> listCourses(User teacher) {
        List<Course> courses = courseRepository.findByTeacherOrderByOrderIndexAscIdAsc(teacher);
        if (courses.isEmpty()) {
            return List.of();
        }
        Map<Long, CourseRevision> latestRevisions = courseRevisionRepository.findLatestByCourseIds(
                        courses.stream().map(Course::getId).toList()).stream()
                .collect(Collectors.toMap(revision -> revision.getCourse().getId(), Function.identity()));
        return courses.stream()
                .map(course -> CourseResponse.from(course, latestRevisions.get(course.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CourseResponse getCourse(User teacher, Long courseId) {
        Course course = findCourse(teacher, courseId);
        return CourseResponse.from(course, findLatestRevision(course));
    }

    @Transactional
    public CourseResponse createCourse(User teacher, CourseRequest request) {
        Course course = new Course();
        course.setTeacher(teacher);
        course.setStatus(ContentStatus.DRAFT);
        applyCourseRequest(course, request);
        Course savedCourse = courseRepository.save(course);
        CourseRevision revision = new CourseRevision();
        revision.setCourse(savedCourse);
        revision.setTeacher(teacher);
        revision.setRevisionNumber(1);
        revision.setStatus(CourseRevisionStatus.DRAFT);
        applyRevisionMetadata(revision, savedCourse);
        CourseRevision savedRevision = courseRevisionRepository.save(revision);
        return CourseResponse.from(savedCourse, savedRevision);
    }

    @Transactional
    public CourseResponse updateCourse(User teacher, Long courseId, CourseRequest request) {
        Course course = findCourse(teacher, courseId);
        ensureEditable(course);
        applyCourseRequest(course, request);
        Course savedCourse = courseRepository.save(course);
        CourseRevision revision = syncEditableRevision(savedCourse);
        return CourseResponse.from(savedCourse, revision);
    }

    @Transactional
    public void deleteCourse(User teacher, Long courseId) {
        Course course = findCourse(teacher, courseId);
        ensureEditable(course);
        courseRepository.delete(course);
    }

    @Transactional
    public TopicResponse createTopic(User teacher, Long courseId, TopicRequest request) {
        Course course = findCourse(teacher, courseId);
        ensureEditable(course);
        Topic topic = new Topic();
        topic.setCourse(course);
        applyTopicRequest(topic, request);
        course.getTopics().add(topic);
        Topic savedTopic = topicRepository.save(topic);
        syncEditableRevision(course);
        return TopicResponse.from(savedTopic);
    }

    @Transactional
    public TopicResponse updateTopic(User teacher, Long topicId, TopicRequest request) {
        Topic topic = findTopic(teacher, topicId);
        ensureEditable(topic.getCourse());
        applyTopicRequest(topic, request);
        Topic savedTopic = topicRepository.save(topic);
        syncEditableRevision(savedTopic.getCourse());
        return TopicResponse.from(savedTopic);
    }

    @Transactional
    public void deleteTopic(User teacher, Long topicId) {
        Topic topic = findTopic(teacher, topicId);
        ensureEditable(topic.getCourse());
        Course course = topic.getCourse();
        topicRepository.delete(topic);
        course.getTopics().remove(topic);
        syncEditableRevision(course);
    }

    @Transactional
    public LessonResponse createLesson(User teacher, Long topicId, LessonRequest request) {
        Topic topic = findTopic(teacher, topicId);
        ensureEditable(topic.getCourse());
        Lesson lesson = new Lesson();
        lesson.setTopic(topic);
        lesson.setTeacher(teacher);
        lesson.setStatus(ContentStatus.DRAFT);
        applyLessonRequest(lesson, request);
        topic.getLessons().add(lesson);
        Lesson savedLesson = lessonRepository.save(lesson);
        syncEditableRevision(topic.getCourse());
        return LessonResponse.from(savedLesson);
    }

    @Transactional
    public LessonResponse updateLesson(User teacher, Long lessonId, LessonRequest request) {
        Lesson lesson = findLesson(teacher, lessonId);
        ensureEditable(lesson.getTopic().getCourse());
        applyLessonRequest(lesson, request);
        Lesson savedLesson = lessonRepository.save(lesson);
        syncEditableRevision(savedLesson.getTopic().getCourse());
        return LessonResponse.from(savedLesson);
    }

    @Transactional
    public void deleteLesson(User teacher, Long lessonId) {
        Lesson lesson = findLesson(teacher, lessonId);
        ensureEditable(lesson.getTopic().getCourse());
        Course course = lesson.getTopic().getCourse();
        lesson.getTopic().getLessons().remove(lesson);
        lessonRepository.delete(lesson);
        syncEditableRevision(course);
    }

    @Transactional
    public QuestionResponse createQuestion(User teacher, Long lessonId, QuestionRequest request) {
        Lesson lesson = findLesson(teacher, lessonId);
        ensureEditable(lesson.getTopic().getCourse());
        validateQuestion(request);
        Question question = new Question();
        question.setLesson(lesson);
        question.setTeacher(teacher);
        question.setStatus(ContentStatus.DRAFT);
        applyQuestionRequest(question, request);
        lesson.getQuestions().add(question);
        Question savedQuestion = questionRepository.save(question);
        syncEditableRevision(lesson.getTopic().getCourse());
        return QuestionResponse.from(savedQuestion);
    }

    @Transactional
    public QuestionResponse updateQuestion(User teacher, Long questionId, QuestionRequest request) {
        Question question = findQuestion(teacher, questionId);
        ensureEditable(question.getLesson().getTopic().getCourse());
        validateQuestion(request);
        question.getOptions().clear();
        applyQuestionRequest(question, request);
        Question savedQuestion = questionRepository.save(question);
        syncEditableRevision(savedQuestion.getLesson().getTopic().getCourse());
        return QuestionResponse.from(savedQuestion);
    }

    @Transactional
    public void deleteQuestion(User teacher, Long questionId) {
        Question question = findQuestion(teacher, questionId);
        ensureEditable(question.getLesson().getTopic().getCourse());
        Course course = question.getLesson().getTopic().getCourse();
        question.getLesson().getQuestions().remove(question);
        questionRepository.delete(question);
        syncEditableRevision(course);
    }

    @Transactional
    public CourseResponse submitCourse(User teacher, Long courseId) {
        Course course = findCourse(teacher, courseId);
        CourseRevision latestRevision = courseRevisionRepository.findTopByCourseIdOrderByRevisionNumberDesc(courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course revision not found."));
        if (!latestRevision.getTeacher().getId().equals(teacher.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Course not found.");
        }
        if (latestRevision.getStatus() != CourseRevisionStatus.DRAFT
                && latestRevision.getStatus() != CourseRevisionStatus.REJECTED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only draft or rejected content can be submitted for review.");
        }
        if (!hasMinimumReviewContent(latestRevision)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Course must have at least one topic, lesson, and question before review.");
        }
        courseRevisionRepository.findByCourseAndStatus(course, CourseRevisionStatus.PENDING_REVIEW).stream()
                .filter(revision -> !revision.getId().equals(latestRevision.getId()))
                .forEach(revision -> revision.setStatus(CourseRevisionStatus.SUPERSEDED));
        latestRevision.setStatus(CourseRevisionStatus.PENDING_REVIEW);
        latestRevision.setSubmittedAt(Instant.now());
        latestRevision.setRejectionReason(null);
        if (course.getPublishedAt() == null) {
            course.setStatus(ContentStatus.PENDING_REVIEW);
            applyCourseMetadata(course, latestRevision);
        }
        return CourseResponse.from(courseRepository.save(course), latestRevision);
    }

    private CourseRevision findLatestRevision(Course course) {
        return courseRevisionRepository.findTopByCourseOrderByRevisionNumberDesc(course)
                .orElse(null);
    }

    private Course findCourse(User teacher, Long courseId) {
        return courseRepository.findByIdAndTeacher(courseId, teacher)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
    }

    private Topic findTopic(User teacher, Long topicId) {
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Topic not found."));
        if (!topic.getCourse().getTeacher().getId().equals(teacher.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Topic not found.");
        }
        return topic;
    }

    private Lesson findLesson(User teacher, Long lessonId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Lesson not found."));
        if (!lesson.getTeacher().getId().equals(teacher.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Lesson not found.");
        }
        return lesson;
    }

    private Question findQuestion(User teacher, Long questionId) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Question not found."));
        if (!question.getTeacher().getId().equals(teacher.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Question not found.");
        }
        return question;
    }

    private void ensureEditable(Course course) {
        if (course.getStatus() != ContentStatus.DRAFT && course.getStatus() != ContentStatus.REJECTED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only draft or rejected content can be edited.");
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

    private void applyCourseMetadata(Course course, CourseRevision revision) {
        course.setName(revision.getName());
        course.setDescription(revision.getDescription());
        course.setType(revision.getType());
        course.setLevel(revision.getLevel());
        course.setOrderIndex(revision.getOrderIndex());
        course.setRejectionReason(null);
    }

    private void applyCourseRequest(Course course, CourseRequest request) {
        course.setName(request.name());
        course.setDescription(request.description());
        course.setType(request.type());
        course.setLevel(request.level());
        course.setOrderIndex(valueOrZero(request.orderIndex()));
        applyPricingRequest(course, request);
    }

    private void applyPricingRequest(Course course, CourseRequest request) {
        CoursePricingType pricingType = request.pricingType() == null ? CoursePricingType.FREE : request.pricingType();
        if (pricingType == CoursePricingType.FREE) {
            course.setPricingType(CoursePricingType.FREE);
            course.setPriceAmountVnd(null);
            course.setProposedPriceAmountVnd(null);
            course.setPriceStatus(CoursePriceStatus.NONE);
            return;
        }
        if (request.proposedPriceAmountVnd() == null || request.proposedPriceAmountVnd() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Paid course requires a positive proposed price.");
        }
        course.setPricingType(CoursePricingType.PAID);
        course.setProposedPriceAmountVnd(request.proposedPriceAmountVnd());
        course.setPriceStatus(CoursePriceStatus.PENDING_REVIEW);
    }

    private void applyRevisionMetadata(CourseRevision revision, Course course) {
        revision.setName(course.getName());
        revision.setDescription(course.getDescription());
        revision.setType(course.getType());
        revision.setLevel(course.getLevel());
        revision.setOrderIndex(course.getOrderIndex());
        revision.setPricingType(course.getPricingType());
        revision.setProposedPriceAmountVnd(course.getProposedPriceAmountVnd());
    }

    private CourseRevision syncEditableRevision(Course course) {
        return latestEditableRevision(course).map(revision -> {
            applyRevisionMetadata(revision, course);
            revision.getTopics().clear();
            course.getTopics().stream()
                    .sorted(Comparator.comparingInt(Topic::getOrderIndex)
                            .thenComparing(Topic::getId, Comparator.nullsLast(Long::compareTo)))
                    .forEach(topic -> revision.getTopics().add(toTopicRevision(revision, topic)));
            return courseRevisionRepository.save(revision);
        }).orElse(null);
    }

    private java.util.Optional<CourseRevision> latestEditableRevision(Course course) {
        return courseRevisionRepository.findTopByCourseAndStatusOrderByRevisionNumberDesc(course, CourseRevisionStatus.DRAFT)
                .or(() -> courseRevisionRepository.findTopByCourseAndStatusOrderByRevisionNumberDesc(
                        course, CourseRevisionStatus.REJECTED));
    }

    private TopicRevision toTopicRevision(CourseRevision revision, Topic topic) {
        TopicRevision topicRevision = new TopicRevision();
        topicRevision.setCourseRevision(revision);
        topicRevision.setSourceTopic(topic);
        topicRevision.setName(topic.getName());
        topicRevision.setDescription(topic.getDescription());
        topicRevision.setOrderIndex(topic.getOrderIndex());
        topic.getLessons().stream()
                .sorted(Comparator.comparingInt(Lesson::getOrderIndex)
                        .thenComparing(Lesson::getId, Comparator.nullsLast(Long::compareTo)))
                .forEach(lesson -> topicRevision.getLessons().add(toLessonRevision(topicRevision, lesson)));
        return topicRevision;
    }

    private LessonRevision toLessonRevision(TopicRevision topicRevision, Lesson lesson) {
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
        lesson.getQuestions().stream()
                .sorted(Comparator.comparingInt(Question::getOrderIndex)
                        .thenComparing(Question::getId, Comparator.nullsLast(Long::compareTo)))
                .forEach(question -> lessonRevision.getQuestions().add(toQuestionRevision(lessonRevision, question)));
        return lessonRevision;
    }

    private QuestionRevision toQuestionRevision(LessonRevision lessonRevision, Question question) {
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
        question.getOptions().stream()
                .sorted(Comparator.comparingInt(QuestionOption::getOrderIndex)
                        .thenComparing(QuestionOption::getId, Comparator.nullsLast(Long::compareTo)))
                .forEach(option -> questionRevision.getOptions().add(toQuestionOptionRevision(questionRevision, option)));
        return questionRevision;
    }

    private QuestionOptionRevision toQuestionOptionRevision(QuestionRevision questionRevision, QuestionOption option) {
        QuestionOptionRevision optionRevision = new QuestionOptionRevision();
        optionRevision.setQuestionRevision(questionRevision);
        optionRevision.setSourceOption(option);
        optionRevision.setOptionText(option.getOptionText());
        optionRevision.setCorrect(option.isCorrect());
        optionRevision.setOrderIndex(option.getOrderIndex());
        return optionRevision;
    }

    private void applyTopicRequest(Topic topic, TopicRequest request) {
        topic.setName(request.name());
        topic.setDescription(request.description());
        topic.setOrderIndex(valueOrZero(request.orderIndex()));
    }

    private void applyLessonRequest(Lesson lesson, LessonRequest request) {
        lesson.setTitle(request.title());
        lesson.setDescription(request.description());
        lesson.setContent(request.content());
        lesson.setSkillType(request.skillType());
        lesson.setOrderIndex(valueOrZero(request.orderIndex()));
        lesson.setXpReward(valueOrZero(request.xpReward()));
        lesson.setPlacementTest(request.placementTest());
    }

    private void applyQuestionRequest(Question question, QuestionRequest request) {
        question.setType(request.type());
        question.setQuestionText(request.questionText());
        question.setExplanation(request.explanation());
        question.setCorrectAnswer(request.correctAnswer());
        question.setKnowledgePoint(request.knowledgePoint());
        question.setOrderIndex(valueOrZero(request.orderIndex()));
        question.setDifficulty(request.difficulty());
        question.setXpReward(valueOrZero(request.xpReward()));
        if (request.options() != null) {
            request.options().forEach(optionRequest -> {
                QuestionOption option = new QuestionOption();
                option.setQuestion(question);
                option.setOptionText(optionRequest.optionText());
                option.setCorrect(optionRequest.correct());
                option.setOrderIndex(valueOrZero(optionRequest.orderIndex()));
                question.getOptions().add(option);
            });
        }
    }

    private void validateQuestion(QuestionRequest request) {
        if (request.type() == QuestionType.MULTIPLE_CHOICE || request.type() == QuestionType.TRUE_FALSE) {
            long correctCount = request.options() == null ? 0 : request.options().stream().filter(QuestionOptionRequest::correct).count();
            if (correctCount != 1 || request.options() == null || request.options().size() < 2) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Multiple choice and true/false questions require exactly one correct option.");
            }
            return;
        }
        if (request.correctAnswer() == null || request.correctAnswer().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Question requires a correct answer.");
        }
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
