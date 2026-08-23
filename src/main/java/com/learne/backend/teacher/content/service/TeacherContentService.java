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
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherContentService {
    private final CourseRepository courseRepository;
    private final TopicRepository topicRepository;
    private final LessonRepository lessonRepository;
    private final QuestionRepository questionRepository;

    public TeacherContentService(CourseRepository courseRepository,
                          TopicRepository topicRepository,
                          LessonRepository lessonRepository,
                          QuestionRepository questionRepository) {
        this.courseRepository = courseRepository;
        this.topicRepository = topicRepository;
        this.lessonRepository = lessonRepository;
        this.questionRepository = questionRepository;
    }

    @Transactional(readOnly = true)
    public List<CourseResponse> listCourses(User teacher) {
        return courseRepository.findByTeacherOrderByOrderIndexAscIdAsc(teacher).stream()
                .map(CourseResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public CourseResponse getCourse(User teacher, Long courseId) {
        return CourseResponse.from(findCourse(teacher, courseId));
    }

    @Transactional
    public CourseResponse createCourse(User teacher, CourseRequest request) {
        Course course = new Course();
        course.setTeacher(teacher);
        course.setStatus(ContentStatus.DRAFT);
        applyCourseRequest(course, request);
        return CourseResponse.from(courseRepository.save(course));
    }

    @Transactional
    public CourseResponse updateCourse(User teacher, Long courseId, CourseRequest request) {
        Course course = findCourse(teacher, courseId);
        ensureEditable(course);
        applyCourseRequest(course, request);
        return CourseResponse.from(courseRepository.save(course));
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
        return TopicResponse.from(topicRepository.save(topic));
    }

    @Transactional
    public TopicResponse updateTopic(User teacher, Long topicId, TopicRequest request) {
        Topic topic = findTopic(teacher, topicId);
        ensureEditable(topic.getCourse());
        applyTopicRequest(topic, request);
        return TopicResponse.from(topicRepository.save(topic));
    }

    @Transactional
    public void deleteTopic(User teacher, Long topicId) {
        Topic topic = findTopic(teacher, topicId);
        ensureEditable(topic.getCourse());
        topicRepository.delete(topic);
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
        return LessonResponse.from(lessonRepository.save(lesson));
    }

    @Transactional
    public LessonResponse updateLesson(User teacher, Long lessonId, LessonRequest request) {
        Lesson lesson = findLesson(teacher, lessonId);
        ensureEditable(lesson.getTopic().getCourse());
        applyLessonRequest(lesson, request);
        return LessonResponse.from(lessonRepository.save(lesson));
    }

    @Transactional
    public void deleteLesson(User teacher, Long lessonId) {
        Lesson lesson = findLesson(teacher, lessonId);
        ensureEditable(lesson.getTopic().getCourse());
        lessonRepository.delete(lesson);
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
        return QuestionResponse.from(questionRepository.save(question));
    }

    @Transactional
    public QuestionResponse updateQuestion(User teacher, Long questionId, QuestionRequest request) {
        Question question = findQuestion(teacher, questionId);
        ensureEditable(question.getLesson().getTopic().getCourse());
        validateQuestion(request);
        question.getOptions().clear();
        applyQuestionRequest(question, request);
        return QuestionResponse.from(questionRepository.save(question));
    }

    @Transactional
    public void deleteQuestion(User teacher, Long questionId) {
        Question question = findQuestion(teacher, questionId);
        ensureEditable(question.getLesson().getTopic().getCourse());
        questionRepository.delete(question);
    }

    @Transactional
    public CourseResponse submitCourse(User teacher, Long courseId) {
        Course course = findCourse(teacher, courseId);
        if (course.getStatus() != ContentStatus.DRAFT && course.getStatus() != ContentStatus.REJECTED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only draft or rejected content can be submitted for review.");
        }
        if (!hasMinimumReviewContent(course)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Course must have at least one topic, lesson, and question before review.");
        }
        course.setStatus(ContentStatus.PENDING_REVIEW);
        course.getTopics().forEach(topic -> topic.getLessons().forEach(lesson -> {
            lesson.setStatus(ContentStatus.PENDING_REVIEW);
            lesson.getQuestions().forEach(question -> question.setStatus(ContentStatus.PENDING_REVIEW));
        }));
        return CourseResponse.from(courseRepository.save(course));
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

    private void applyCourseRequest(Course course, CourseRequest request) {
        course.setName(request.name());
        course.setDescription(request.description());
        course.setType(request.type());
        course.setLevel(request.level());
        course.setOrderIndex(valueOrZero(request.orderIndex()));
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
