package com.learne.backend.teacher.content.service;

import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.teacher.content.submission.SubmissionGuard;
import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.shared.pagination.CursorPageResponse;
import com.learne.backend.shared.pagination.CursorTokenService;
import com.learne.backend.teacher.content.dto.CourseRequest;
import com.learne.backend.teacher.content.dto.CourseResponse;
import com.learne.backend.teacher.content.dto.LessonRequest;
import com.learne.backend.teacher.content.dto.LessonResponse;
import com.learne.backend.teacher.content.dto.QuestionOptionRequest;
import com.learne.backend.teacher.content.dto.QuestionRequest;
import com.learne.backend.teacher.content.dto.QuestionResponse;
import com.learne.backend.teacher.content.dto.TopicRequest;
import com.learne.backend.teacher.content.dto.TopicResponse;
import com.learne.backend.teacher.content.enrichment.CourseReviewEnrichmentService;
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
import com.learne.backend.teacher.content.repository.LessonRevisionRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.content.repository.QuestionRevisionRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
import com.learne.backend.teacher.content.repository.TopicRevisionRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherContentService {
    private static final int FIRST_PAGE_ASC_CURSOR_ORDER_INDEX = Integer.MIN_VALUE;
    private static final long FIRST_PAGE_ASC_CURSOR_ID = 0L;

    private final CourseRepository courseRepository;
    private final CourseRevisionRepository courseRevisionRepository;
    private final TopicRepository topicRepository;
    private final LessonRepository lessonRepository;
    private final QuestionRepository questionRepository;
    private final TopicRevisionRepository topicRevisionRepository;
    private final LessonRevisionRepository lessonRevisionRepository;
    private final QuestionRevisionRepository questionRevisionRepository;
    private final CacheInvalidationService cacheInvalidationService;
    private final CursorTokenService cursorTokenService;
    private final CourseWriteLockService locks;
    private final RevisionAudioRebuildGuard revisionAudioRebuildGuard;
    private final SubmissionGuard submissionGuard;
    private final com.learne.backend.teacher.content.submission.SubmissionRepository submissions;
    private final com.learne.backend.teacher.content.audio.service.MediaCleanupProducer cleanup;

    public TeacherContentService(CourseRepository courseRepository,
                           CourseRevisionRepository courseRevisionRepository,
                             TopicRepository topicRepository,
                             LessonRepository lessonRepository,
                             QuestionRepository questionRepository,
                             TopicRevisionRepository topicRevisionRepository,
                             LessonRevisionRepository lessonRevisionRepository,
                             QuestionRevisionRepository questionRevisionRepository,
                             CacheInvalidationService cacheInvalidationService,
                             CursorTokenService cursorTokenService,
                             CourseWriteLockService locks,
                             RevisionAudioRebuildGuard revisionAudioRebuildGuard, SubmissionGuard submissionGuard,
                             com.learne.backend.teacher.content.submission.SubmissionRepository submissions,
                             com.learne.backend.teacher.content.audio.service.MediaCleanupProducer cleanup) {
        this.courseRepository = courseRepository;
        this.courseRevisionRepository = courseRevisionRepository;
        this.topicRepository = topicRepository;
        this.lessonRepository = lessonRepository;
        this.questionRepository = questionRepository;
        this.topicRevisionRepository = topicRevisionRepository;
        this.lessonRevisionRepository = lessonRevisionRepository;
        this.questionRevisionRepository = questionRevisionRepository;
        this.cacheInvalidationService = cacheInvalidationService;
        this.cursorTokenService = cursorTokenService;
        this.locks = locks;
        this.revisionAudioRebuildGuard = revisionAudioRebuildGuard;
        this.submissionGuard = submissionGuard;
        this.submissions = submissions;
        this.cleanup = cleanup;
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<CourseResponse> listCourses(
            User teacher, ContentStatus status, String query, int size, String cursor) {
        String normalizedQuery = query == null || query.isBlank() ? null : query.trim();
        Map<String, Object> filters = Map.of(
                "status", status == null ? "" : status.name(),
                "q", normalizedQuery == null ? "" : normalizedQuery);
        String queryParameter = normalizedQuery == null ? "" : normalizedQuery;
        Integer cursorOrderIndex = FIRST_PAGE_ASC_CURSOR_ORDER_INDEX;
        Long cursorId = FIRST_PAGE_ASC_CURSOR_ID;
        if (cursor != null && !cursor.isBlank()) {
            CursorTokenService.DecodedCursor decoded = cursorTokenService.decode(
                    cursor, "teacher-courses", "orderIndexAscIdAsc", filters);
            cursorOrderIndex = Integer.valueOf(decoded.sortValues().get("orderIndex"));
            cursorId = Long.valueOf(decoded.sortValues().get("id"));
        }
        List<Course> courses = courseRepository.findTeacherCoursePage(
                teacher, status, queryParameter, cursorOrderIndex, cursorId, PageRequest.of(0, size + 1));
        boolean hasNext = courses.size() > size;
        List<Course> pageCourses = hasNext ? courses.subList(0, size) : courses;
        if (pageCourses.isEmpty()) {
            return new CursorPageResponse<>(List.of(), null, false, size);
        }
        Map<Long, CourseRevision> latestRevisions = courseRevisionRepository.findLatestByCourseIds(
                        pageCourses.stream().map(Course::getId).toList()).stream()
                .collect(Collectors.toMap(revision -> revision.getCourse().getId(), Function.identity()));
        var summaries = submissions.latestSummaries(pageCourses.stream().map(Course::getId).toList());
        List<CourseResponse> content = pageCourses.stream()
                .map(course -> teacherCourseResponse(course, latestRevisions.get(course.getId())).withSubmission(summaries.get(course.getId())))
                .toList();
        String nextCursor = null;
        if (hasNext) {
            Course last = pageCourses.get(pageCourses.size() - 1);
            nextCursor = cursorTokenService.encode(
                    "teacher-courses",
                    "orderIndexAscIdAsc",
                    filters,
                    Map.of("orderIndex", last.getOrderIndex(), "id", last.getId()));
        }
        return new CursorPageResponse<>(content, nextCursor, hasNext, size);
    }

    @Transactional(readOnly = true)
    public CourseResponse getCourse(User teacher, Long courseId) {
        Course course = findCourse(teacher, courseId);
        return teacherCourseResponse(course, findLatestRevision(course)).withSubmission(submissions.latestSummaries(List.of(courseId)).get(courseId));
    }

    @Transactional
    public CourseResponse createCourse(User teacher, CourseRequest request) {
        Course course = new Course();
        course.setTeacher(teacher);
        course.setStatus(ContentStatus.DRAFT);
        applyCourseRequest(course, request, true);
        Course savedCourse = courseRepository.save(course);
        CourseRevision revision = new CourseRevision();
        revision.setCourse(savedCourse);
        revision.setTeacher(teacher);
        revision.setRevisionNumber(1);
        revision.setStatus(CourseRevisionStatus.DRAFT);
        applyRevisionMetadata(revision, savedCourse);
        CourseRevision savedRevision = courseRevisionRepository.save(revision);
        CourseResponse response = CourseResponse.from(savedCourse, savedRevision);
        evictContentCaches(teacher);
        return response;
    }

    @Transactional
    public CourseResponse createEditRevision(User teacher, Long courseId) {
        Course course = locks.teacherCourse(courseId, teacher);
        submissionGuard.requireEditable(courseId);
        CourseRevision latestRevision = courseRevisionRepository.findLockedLatestByCourseId(courseId, PageRequest.of(0, 1)).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course revision not found."));
        if (!latestRevision.getTeacher().getId().equals(teacher.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Course not found.");
        }
        if (isEditableRevision(latestRevision)) {
            return CourseResponse.fromRevisionBackedCourse(course, latestRevision);
        }
        if (latestRevision.getStatus() != CourseRevisionStatus.APPROVED
                && latestRevision.getStatus() != CourseRevisionStatus.PENDING_REVIEW) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Course cannot be edited in its current state.");
        }
        CourseRevision draft = cloneRevisionForEdit(course, teacher, latestRevision);
        if (latestRevision.getStatus() == CourseRevisionStatus.PENDING_REVIEW) {
            latestRevision.setStatus(CourseRevisionStatus.SUPERSEDED);
            if (course.getPublishedAt() == null) {
                course.setStatus(ContentStatus.DRAFT);
            }
        }
        CourseRevision savedDraft = courseRevisionRepository.save(draft);
        evictContentCaches(teacher);
        return CourseResponse.fromRevisionBackedCourse(course, savedDraft);
    }

    @Transactional
    public CourseResponse updateCourse(User teacher, Long courseId, CourseRequest request) {
        Course course = locks.teacherCourse(courseId, teacher);
        submissionGuard.requireEditable(courseId);
        CourseRevision latestRevision = findLatestRevision(course);
        if (course.getPublishedAt() != null || course.getStatus() == ContentStatus.APPROVED) {
            CourseRevision editableRevision = latestLockedEditableRevision(courseId)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Create an edit revision before editing this course."));
            applyCourseRequestToRevision(editableRevision, request, false);
            CourseRevision savedRevision = courseRevisionRepository.save(editableRevision);
            CourseResponse response = CourseResponse.fromRevisionBackedCourse(course, savedRevision);
            evictContentCaches(teacher);
            return response;
        }
        ensureEditable(course);
        CourseRevision rebuildRevision = assertSafeRevisionRebuild(course, Set.of());
        applyCourseRequest(course, request, false);
        Course savedCourse = courseRepository.save(course);
        CourseRevision revision = syncEditableRevision(savedCourse, rebuildRevision);
        CourseResponse response = teacherCourseResponse(savedCourse, revision == null ? latestRevision : revision);
        evictContentCaches(teacher);
        return response;
    }

    @Transactional
    public void deleteCourse(User teacher, Long courseId) {
        Course course = locks.teacherCourse(courseId, teacher);
        submissionGuard.requireEditable(courseId);
        ensureEditable(course);
        courseRepository.delete(course);
        evictContentCaches(teacher);
    }

    @Transactional
    public TopicResponse createTopic(User teacher, Long courseId, TopicRequest request) {
        Course course = locks.teacherCourse(courseId, teacher);
        submissionGuard.requireEditable(courseId);
        if (isPublishedOrApproved(course)) {
            CourseRevision revision = latestLockedEditableRevision(courseId)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Create an edit revision before editing this course."));
            TopicRevision topic = new TopicRevision();
            topic.setCourseRevision(revision);
            applyTopicRequest(topic, request);
            revision.getTopics().add(topic);
            TopicRevision savedTopic = topicRevisionRepository.saveAndFlush(topic);
            evictContentCaches(teacher);
            return TopicResponse.from(savedTopic);
        }
        ensureEditable(course);
        Topic topic = new Topic();
        CourseRevision rebuildRevision = assertSafeRevisionRebuild(course, Set.of());
        topic.setCourse(course);
        applyTopicRequest(topic, request);
        course.getTopics().add(topic);
        Topic savedTopic = topicRepository.save(topic);
        syncEditableRevision(course, rebuildRevision);
        TopicResponse response = TopicResponse.from(savedTopic);
        evictContentCaches(teacher);
        return response;
    }

    @Transactional
    public TopicResponse updateTopic(User teacher, Long topicId, TopicRequest request) {
        Optional<Topic> liveTopic = lockTopicTarget(teacher, topicId)
                ? Optional.of(findTopic(teacher, topicId)) : Optional.empty();
        if (liveTopic.isPresent()) {
            Topic topic = liveTopic.get();
            ensureEditable(topic.getCourse());
            CourseRevision rebuildRevision = assertSafeRevisionRebuild(topic.getCourse(), Set.of());
            applyTopicRequest(topic, request);
            Topic savedTopic = topicRepository.save(topic);
            syncEditableRevision(savedTopic.getCourse(), rebuildRevision);
            TopicResponse response = TopicResponse.from(savedTopic);
            evictContentCaches(teacher);
            return response;
        }
        Optional<TopicRevision> editableTopicRevision = findEditableTopicRevision(teacher, topicId);
        if (editableTopicRevision.isPresent()) {
            TopicRevision topic = editableTopicRevision.get();
            ensureLockedLatestEditableRevision(teacher, topic.getCourseRevision());
            applyTopicRequest(topic, request);
            TopicResponse response = TopicResponse.from(topicRevisionRepository.save(topic));
            evictContentCaches(teacher);
            return response;
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "Topic not found.");
    }

    @Transactional
    public void deleteTopic(User teacher, Long topicId) {
        Optional<Topic> liveTopic = lockTopicTarget(teacher, topicId)
                ? Optional.of(findTopic(teacher, topicId)) : Optional.empty();
        if (liveTopic.isPresent()) {
            Topic topic = liveTopic.get();
            ensureEditable(topic.getCourse());
            Course course = topic.getCourse();
            CourseRevision rebuildRevision = assertSafeRevisionRebuild(course,
                    topic.getLessons().stream().map(Lesson::getId).collect(Collectors.toSet()));
            var detached = new java.util.ArrayList<>(topic.getLessons().stream()
                    .map(com.learne.backend.teacher.content.audio.dto.LessonAudioState::from).toList());
            detached.addAll(revisionAudio(rebuildRevision));
            topicRepository.delete(topic);
            course.getTopics().remove(topic);
            syncEditableRevision(course, rebuildRevision);
            enqueueDetached(course.getId(), detached, revisionAudio(rebuildRevision));
            evictContentCaches(teacher);
            return;
        }
        TopicRevision topic = findEditableTopicRevision(teacher, topicId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Topic not found."));
        CourseRevision revision = topic.getCourseRevision();
        ensureLockedLatestEditableRevision(teacher, revision);
        var detached = topic.getLessons().stream().map(com.learne.backend.teacher.content.audio.dto.LessonAudioState::from).toList();
        revision.getTopics().remove(topic);
        courseRevisionRepository.save(revision);
        enqueueDetached(revision.getCourse().getId(), detached, List.of());
        evictContentCaches(teacher);
    }

    @Transactional
    public LessonResponse createLesson(User teacher, Long topicId, LessonRequest request) {
        Optional<Topic> liveTopic = lockTopicTarget(teacher, topicId)
                ? Optional.of(findTopic(teacher, topicId)) : Optional.empty();
        if (liveTopic.isEmpty()) {
            TopicRevision topic = findEditableTopicRevision(teacher, topicId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Topic not found."));
            ensureLockedLatestEditableRevision(teacher, topic.getCourseRevision());
            LessonRevision lesson = new LessonRevision();
            lesson.setTopicRevision(topic);
            lesson.setTeacher(teacher);
            applyLessonRequest(lesson, request);
            topic.getLessons().add(lesson);
            LessonRevision savedLesson = lessonRevisionRepository.saveAndFlush(lesson);
            LessonResponse response = LessonResponse.from(savedLesson);
            evictContentCaches(teacher);
            return response;
        }
        Topic topic = liveTopic.get();
        ensureEditable(topic.getCourse());
        CourseRevision rebuildRevision = assertSafeRevisionRebuild(topic.getCourse(), Set.of());
        Lesson lesson = new Lesson();
        lesson.setTopic(topic);
        lesson.setTeacher(teacher);
        lesson.setStatus(ContentStatus.DRAFT);
        applyLessonRequest(lesson, request);
        topic.getLessons().add(lesson);
        Lesson savedLesson = lessonRepository.save(lesson);
        syncEditableRevision(topic.getCourse(), rebuildRevision);
        LessonResponse response = LessonResponse.from(savedLesson);
        evictContentCaches(teacher);
        return response;
    }

    @Transactional
    public LessonResponse updateLesson(User teacher, Long lessonId, LessonRequest request) {
        Optional<Lesson> liveLesson = lockLessonTarget(teacher, lessonId)
                ? Optional.of(findLesson(teacher, lessonId)) : Optional.empty();
        if (liveLesson.isPresent()) {
            Lesson lesson = liveLesson.get();
            ensureEditable(lesson.getTopic().getCourse());
            CourseRevision rebuildRevision = assertSafeRevisionRebuild(lesson.getTopic().getCourse(), Set.of());
            applyLessonRequest(lesson, request);
            Lesson savedLesson = lessonRepository.save(lesson);
            syncEditableRevision(savedLesson.getTopic().getCourse(), rebuildRevision);
            LessonResponse response = LessonResponse.from(savedLesson);
            evictContentCaches(teacher);
            return response;
        }
        Optional<LessonRevision> editableLessonRevision = findEditableLessonRevision(teacher, lessonId);
        if (editableLessonRevision.isPresent()) {
            LessonRevision lesson = editableLessonRevision.get();
            ensureLockedLatestEditableRevision(teacher, lesson.getTopicRevision().getCourseRevision());
            applyLessonRequest(lesson, request);
            LessonResponse response = LessonResponse.from(lessonRevisionRepository.save(lesson));
            evictContentCaches(teacher);
            return response;
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "Lesson not found.");
    }

    @Transactional
    public void deleteLesson(User teacher, Long lessonId) {
        Optional<Lesson> liveLesson = lockLessonTarget(teacher, lessonId)
                ? Optional.of(findLesson(teacher, lessonId)) : Optional.empty();
        if (liveLesson.isPresent()) {
            Lesson lesson = liveLesson.get();
            ensureEditable(lesson.getTopic().getCourse());
            Course course = lesson.getTopic().getCourse();
            CourseRevision rebuildRevision = assertSafeRevisionRebuild(course, Set.of(lessonId));
            var detached = new java.util.ArrayList<>(revisionAudio(rebuildRevision));
            detached.add(com.learne.backend.teacher.content.audio.dto.LessonAudioState.from(lesson));
            lesson.getTopic().getLessons().remove(lesson);
            lessonRepository.delete(lesson);
            syncEditableRevision(course, rebuildRevision);
            enqueueDetached(course.getId(), detached, revisionAudio(rebuildRevision));
            evictContentCaches(teacher);
            return;
        }
        LessonRevision lesson = findEditableLessonRevision(teacher, lessonId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Lesson not found."));
        TopicRevision topic = lesson.getTopicRevision();
        ensureLockedLatestEditableRevision(teacher, topic.getCourseRevision());
        var detached = List.of(com.learne.backend.teacher.content.audio.dto.LessonAudioState.from(lesson));
        topic.getLessons().remove(lesson);
        courseRevisionRepository.save(topic.getCourseRevision());
        enqueueDetached(topic.getCourseRevision().getCourse().getId(), detached, List.of());
        evictContentCaches(teacher);
    }

    @Transactional
    public QuestionResponse createQuestion(User teacher, Long lessonId, QuestionRequest request) {
        validateQuestion(request);
        Optional<Lesson> liveLesson = lockLessonTarget(teacher, lessonId)
                ? Optional.of(findLesson(teacher, lessonId)) : Optional.empty();
        if (liveLesson.isEmpty()) {
            LessonRevision lesson = findEditableLessonRevision(teacher, lessonId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Lesson not found."));
            ensureLockedLatestEditableRevision(teacher, lesson.getTopicRevision().getCourseRevision());
            QuestionRevision question = new QuestionRevision();
            question.setLessonRevision(lesson);
            question.setTeacher(teacher);
            applyQuestionRequest(question, request);
            lesson.getQuestions().add(question);
            QuestionRevision savedQuestion = questionRevisionRepository.saveAndFlush(question);
            QuestionResponse response = QuestionResponse.from(savedQuestion);
            evictContentCaches(teacher);
            return response;
        }
        Lesson lesson = liveLesson.get();
        ensureEditable(lesson.getTopic().getCourse());
        CourseRevision rebuildRevision = assertSafeRevisionRebuild(lesson.getTopic().getCourse(), Set.of());
        Question question = new Question();
        question.setLesson(lesson);
        question.setTeacher(teacher);
        question.setStatus(ContentStatus.DRAFT);
        applyQuestionRequest(question, request);
        lesson.getQuestions().add(question);
        Question savedQuestion = questionRepository.save(question);
        syncEditableRevision(lesson.getTopic().getCourse(), rebuildRevision);
        QuestionResponse response = QuestionResponse.from(savedQuestion);
        evictContentCaches(teacher);
        return response;
    }

    @Transactional
    public QuestionResponse updateQuestion(User teacher, Long questionId, QuestionRequest request) {
        Optional<Question> liveQuestion = lockQuestionTarget(teacher, questionId)
                ? Optional.of(findQuestion(teacher, questionId)) : Optional.empty();
        if (liveQuestion.isPresent()) {
            Question question = liveQuestion.get();
            ensureEditable(question.getLesson().getTopic().getCourse());
            validateQuestion(request);
            CourseRevision rebuildRevision = assertSafeRevisionRebuild(question.getLesson().getTopic().getCourse(), Set.of());
            question.getOptions().clear();
            applyQuestionRequest(question, request);
            Question savedQuestion = questionRepository.save(question);
            syncEditableRevision(savedQuestion.getLesson().getTopic().getCourse(), rebuildRevision);
            QuestionResponse response = QuestionResponse.from(savedQuestion);
            evictContentCaches(teacher);
            return response;
        }
        Optional<QuestionRevision> editableQuestionRevision = findEditableQuestionRevision(teacher, questionId);
        if (editableQuestionRevision.isPresent()) {
            QuestionRevision question = editableQuestionRevision.get();
            ensureLockedLatestEditableRevision(teacher, question.getLessonRevision().getTopicRevision().getCourseRevision());
            validateQuestion(request);
            question.getOptions().clear();
            applyQuestionRequest(question, request);
            QuestionResponse response = QuestionResponse.from(questionRevisionRepository.save(question));
            evictContentCaches(teacher);
            return response;
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "Question not found.");
    }

    @Transactional
    public void deleteQuestion(User teacher, Long questionId) {
        Optional<Question> liveQuestion = lockQuestionTarget(teacher, questionId)
                ? Optional.of(findQuestion(teacher, questionId)) : Optional.empty();
        if (liveQuestion.isPresent()) {
            Question question = liveQuestion.get();
            ensureEditable(question.getLesson().getTopic().getCourse());
            Course course = question.getLesson().getTopic().getCourse();
            CourseRevision rebuildRevision = assertSafeRevisionRebuild(course, Set.of());
            question.getLesson().getQuestions().remove(question);
            questionRepository.delete(question);
            syncEditableRevision(course, rebuildRevision);
            evictContentCaches(teacher);
            return;
        }
        QuestionRevision question = findEditableQuestionRevision(teacher, questionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Question not found."));
        LessonRevision lesson = question.getLessonRevision();
        ensureLockedLatestEditableRevision(teacher, lesson.getTopicRevision().getCourseRevision());
        lesson.getQuestions().remove(question);
        courseRevisionRepository.save(lesson.getTopicRevision().getCourseRevision());
        evictContentCaches(teacher);
    }

    private void evictContentCaches(User teacher) {
        cacheInvalidationService.evictTeacherDashboard(teacher.getId());
        cacheInvalidationService.evictGlobalCourseCatalog();
        cacheInvalidationService.evictAdminDashboard();
    }

    private CourseRevision findLatestRevision(Course course) {
        return courseRevisionRepository.findTopByCourseOrderByRevisionNumberDesc(course)
                .orElse(null);
    }

    private CourseResponse teacherCourseResponse(Course course, CourseRevision latestRevision) {
        boolean publishedCourse = course.getPublishedAt() != null || course.getStatus() == ContentStatus.APPROVED;
        if (publishedCourse && latestRevision != null && (isEditableRevision(latestRevision)
                || latestRevision.getStatus() == CourseRevisionStatus.PENDING_REVIEW)) {
            return CourseResponse.fromRevisionBackedCourse(course, latestRevision);
        }
        return CourseResponse.from(course, latestRevision);
    }

    private Course findCourse(User teacher, Long courseId) {
        return courseRepository.findByIdAndTeacher(courseId, teacher)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Course not found."));
    }

    private Topic findTopic(User teacher, Long topicId) {
        return findTopicOptional(teacher, topicId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Topic not found."));
    }

    private Lesson findLesson(User teacher, Long lessonId) {
        return findLessonOptional(teacher, lessonId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Lesson not found."));
    }

    private Question findQuestion(User teacher, Long questionId) {
        return findQuestionOptional(teacher, questionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Question not found."));
    }

    private Optional<Topic> findTopicOptional(User teacher, Long topicId) {
        return topicRepository.findById(topicId)
                .filter(topic -> topic.getCourse().getTeacher().getId().equals(teacher.getId()));
    }

    private Optional<Lesson> findLessonOptional(User teacher, Long lessonId) {
        return lessonRepository.findById(lessonId)
                .filter(lesson -> lesson.getTeacher().getId().equals(teacher.getId()));
    }

    private Optional<Question> findQuestionOptional(User teacher, Long questionId) {
        return questionRepository.findById(questionId)
                .filter(question -> question.getTeacher().getId().equals(teacher.getId()));
    }

    private Optional<TopicRevision> findEditableTopicRevision(User teacher, Long topicId) {
        return topicRevisionRepository.findEditableLatestForTeacher(teacher.getId(), topicId);
    }

    private Optional<LessonRevision> findEditableLessonRevision(User teacher, Long lessonId) {
        return lessonRevisionRepository.findEditableLatestForTeacher(teacher.getId(), lessonId);
    }

    private Optional<QuestionRevision> findEditableQuestionRevision(User teacher, Long questionId) {
        return questionRevisionRepository.findEditableLatestForTeacher(teacher.getId(), questionId);
    }

    private void ensureEditable(Course course) {
        if (!isEditableCourse(course)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only draft or rejected content can be edited.");
        }
    }

    private boolean isEditableCourse(Course course) {
        return course.getStatus() == ContentStatus.DRAFT || course.getStatus() == ContentStatus.REJECTED;
    }

    private boolean isPublishedOrApproved(Course course) {
        return course.getPublishedAt() != null || course.getStatus() == ContentStatus.APPROVED;
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

    private void applyCourseRequest(Course course, CourseRequest request, boolean defaultMissingPricingTypeToFree) {
        course.setName(request.name());
        course.setDescription(request.description());
        course.setType(request.type());
        course.setLevel(request.level());
        course.setOrderIndex(valueOrZero(request.orderIndex()));
        applyPricingRequest(course, request, defaultMissingPricingTypeToFree);
    }

    private void applyCourseRequestToRevision(CourseRevision revision, CourseRequest request, boolean defaultMissingPricingTypeToFree) {
        revision.setName(request.name());
        revision.setDescription(request.description());
        revision.setType(request.type());
        revision.setLevel(request.level());
        revision.setOrderIndex(valueOrZero(request.orderIndex()));
        if (request.pricingType() == null && !defaultMissingPricingTypeToFree) {
            return;
        }
        CoursePricingType pricingType = request.pricingType() == null ? CoursePricingType.FREE : request.pricingType();
        revision.setPricingType(pricingType);
        if (pricingType == CoursePricingType.FREE) {
            revision.setProposedPriceAmountVnd(null);
            return;
        }
        if (request.proposedPriceAmountVnd() == null || request.proposedPriceAmountVnd() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Paid course requires a positive proposed price.");
        }
        revision.setProposedPriceAmountVnd(request.proposedPriceAmountVnd());
    }

    private void applyPricingRequest(Course course, CourseRequest request, boolean defaultMissingPricingTypeToFree) {
        if (request.pricingType() == null && !defaultMissingPricingTypeToFree) {
            return;
        }
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

    // All callers hold the course lock and invoke this before changing the managed live graph.
    private CourseRevision assertSafeRevisionRebuild(Course course, Set<Long> deliberatelyDeletedLiveLessonIds) {
        return latestEditableRevision(course).map(revision -> {
            revisionAudioRebuildGuard.assertSafe(course, revision, deliberatelyDeletedLiveLessonIds);
            return revision;
        }).orElse(null);
    }

    private CourseRevision syncEditableRevision(Course course, CourseRevision rebuildRevision) {
        return Optional.ofNullable(rebuildRevision).map(revision -> {
            applyRevisionMetadata(revision, course);
            revision.getTopics().clear();
            course.getTopics().stream()
                    .sorted(Comparator.comparingInt(Topic::getOrderIndex)
                            .thenComparing(Topic::getId, Comparator.nullsLast(Long::compareTo)))
                    .forEach(topic -> revision.getTopics().add(toTopicRevision(revision, topic)));
            return courseRevisionRepository.save(revision);
        }).orElse(null);
    }

    private List<com.learne.backend.teacher.content.audio.dto.LessonAudioState> revisionAudio(CourseRevision revision) {
        return revision == null ? List.of() : revision.getTopics().stream().flatMap(t -> t.getLessons().stream())
                .map(com.learne.backend.teacher.content.audio.dto.LessonAudioState::from).toList();
    }

    private void enqueueDetached(Long courseId, List<com.learne.backend.teacher.content.audio.dto.LessonAudioState> before,
            List<com.learne.backend.teacher.content.audio.dto.LessonAudioState> remaining) {
        var detached = before.stream().filter(s -> s.publicId() != null && !remaining.contains(s)).toList();
        if (!detached.isEmpty()) cleanup.enqueue(java.util.UUID.randomUUID(), courseId, detached);
    }

    private Optional<CourseRevision> latestEditableRevision(Course course) {
        return courseRevisionRepository.findTopByCourseOrderByRevisionNumberDesc(course)
                .filter(this::isEditableRevision);
    }

    private Optional<CourseRevision> latestLockedEditableRevision(Long courseId) {
        return courseRevisionRepository.findLockedLatestByCourseId(courseId, PageRequest.of(0, 1)).stream().findFirst()
                .filter(this::isEditableRevision);
    }

    private void ensureLockedLatestEditableRevision(User teacher, CourseRevision revision) {
        Long courseId = revision.getCourse().getId();
        CourseRevision latestRevision = latestLockedEditableRevision(courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Create an edit revision before editing this course."));
        if (!latestRevision.getId().equals(revision.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only the latest edit revision can be edited.");
        }
    }

    private boolean isEditableRevision(CourseRevision revision) {
        return revision.getStatus() == CourseRevisionStatus.DRAFT
                || revision.getStatus() == CourseRevisionStatus.REJECTED;
    }

    // Preserve the legacy live-first namespace choice, then reload that same type after locking.
    private boolean lockTopicTarget(User teacher, Long id) {
        Optional<Long> live = topicRepository.findCourseIdByTopicIdAndTeacherId(id, teacher.getId());
        Long courseId = live.or(() -> topicRevisionRepository.findCourseIdByTopicIdAndTeacherId(id, teacher.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Topic not found."));
        locks.teacherCourse(courseId, teacher);
        submissionGuard.requireEditable(courseId);
        return live.isPresent();
    }

    private boolean lockLessonTarget(User teacher, Long id) {
        Optional<Long> live = lessonRepository.findCourseIdByLessonIdAndTeacherId(id, teacher.getId());
        Long courseId = live.or(() -> lessonRevisionRepository.findCourseIdByLessonIdAndTeacherId(id, teacher.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Lesson not found."));
        locks.teacherCourse(courseId, teacher);
        submissionGuard.requireEditable(courseId);
        return live.isPresent();
    }

    private boolean lockQuestionTarget(User teacher, Long id) {
        Optional<Long> live = questionRepository.findCourseIdByQuestionIdAndTeacherId(id, teacher.getId());
        Long courseId = live.or(() -> questionRevisionRepository.findCourseIdByQuestionIdAndTeacherId(id, teacher.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Question not found."));
        locks.teacherCourse(courseId, teacher);
        submissionGuard.requireEditable(courseId);
        return live.isPresent();
    }

    private CourseRevision cloneRevisionForEdit(Course course, User teacher, CourseRevision source) {
        CourseRevision revision = new CourseRevision();
        revision.setCourse(course);
        revision.setTeacher(teacher);
        revision.setRevisionNumber(source.getRevisionNumber() + 1);
        revision.setStatus(CourseRevisionStatus.DRAFT);
        revision.setName(source.getName());
        revision.setDescription(source.getDescription());
        revision.setType(source.getType());
        revision.setLevel(source.getLevel());
        revision.setOrderIndex(source.getOrderIndex());
        revision.setPricingType(source.getPricingType());
        revision.setProposedPriceAmountVnd(source.getProposedPriceAmountVnd());
        source.getTopics().stream()
                .sorted(Comparator.comparingInt(TopicRevision::getOrderIndex)
                        .thenComparing(TopicRevision::getId, Comparator.nullsLast(Long::compareTo)))
                .forEach(topic -> revision.getTopics().add(cloneTopicRevision(revision, topic)));
        return revision;
    }

    private TopicRevision cloneTopicRevision(CourseRevision targetRevision, TopicRevision source) {
        TopicRevision clone = new TopicRevision();
        clone.setCourseRevision(targetRevision);
        clone.setSourceTopic(source.getSourceTopic());
        clone.setName(source.getName());
        clone.setDescription(source.getDescription());
        clone.setOrderIndex(source.getOrderIndex());
        source.getLessons().stream()
                .sorted(Comparator.comparingInt(LessonRevision::getOrderIndex)
                        .thenComparing(LessonRevision::getId, Comparator.nullsLast(Long::compareTo)))
                .forEach(lesson -> clone.getLessons().add(cloneLessonRevision(clone, lesson)));
        return clone;
    }

    private LessonRevision cloneLessonRevision(TopicRevision targetTopic, LessonRevision source) {
        LessonRevision clone = new LessonRevision();
        clone.setTopicRevision(targetTopic);
        clone.setSourceLesson(source.getSourceLesson());
        clone.setTeacher(source.getTeacher());
        clone.setTitle(source.getTitle());
        clone.setDescription(source.getDescription());
        clone.setContent(source.getContent());
        clone.setAudioUrl(source.getAudioUrl());
        clone.setAudioPublicId(source.getAudioPublicId());
        clone.setSkillType(source.getSkillType());
        clone.setOrderIndex(source.getOrderIndex());
        clone.setXpReward(source.getXpReward());
        clone.setPlacementTest(source.isPlacementTest());
        source.getQuestions().stream()
                .sorted(Comparator.comparingInt(QuestionRevision::getOrderIndex)
                        .thenComparing(QuestionRevision::getId, Comparator.nullsLast(Long::compareTo)))
                .forEach(question -> clone.getQuestions().add(cloneQuestionRevision(clone, question)));
        return clone;
    }

    private QuestionRevision cloneQuestionRevision(LessonRevision targetLesson, QuestionRevision source) {
        QuestionRevision clone = new QuestionRevision();
        clone.setLessonRevision(targetLesson);
        clone.setSourceQuestion(source.getSourceQuestion());
        clone.setTeacher(source.getTeacher());
        clone.setType(source.getType());
        clone.setQuestionText(source.getQuestionText());
        clone.setExplanation(source.getExplanation());
        clone.setCorrectAnswer(source.getCorrectAnswer());
        clone.setKnowledgePoint(source.getKnowledgePoint());
        clone.setOrderIndex(source.getOrderIndex());
        clone.setDifficulty(source.getDifficulty());
        clone.setXpReward(source.getXpReward());
        source.getOptions().stream()
                .sorted(Comparator.comparingInt(QuestionOptionRevision::getOrderIndex)
                        .thenComparing(QuestionOptionRevision::getId, Comparator.nullsLast(Long::compareTo)))
                .forEach(option -> clone.getOptions().add(cloneQuestionOptionRevision(clone, option)));
        return clone;
    }

    private QuestionOptionRevision cloneQuestionOptionRevision(QuestionRevision targetQuestion, QuestionOptionRevision source) {
        QuestionOptionRevision clone = new QuestionOptionRevision();
        clone.setQuestionRevision(targetQuestion);
        clone.setSourceOption(source.getSourceOption());
        clone.setOptionText(source.getOptionText());
        clone.setCorrect(source.isCorrect());
        clone.setOrderIndex(source.getOrderIndex());
        return clone;
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

    private void applyTopicRequest(TopicRevision topic, TopicRequest request) {
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

    private void applyLessonRequest(LessonRevision lesson, LessonRequest request) {
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

    private void applyQuestionRequest(QuestionRevision question, QuestionRequest request) {
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
                QuestionOptionRevision option = new QuestionOptionRevision();
                option.setQuestionRevision(question);
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
