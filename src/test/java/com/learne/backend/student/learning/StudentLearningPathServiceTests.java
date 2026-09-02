package com.learne.backend.student.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.ai.AiLearningPathClient;
import com.learne.backend.student.learning.dto.NextLessonRecommendationResponse;
import com.learne.backend.student.learning.dto.StudentLearningPathResponse;
import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.entity.LearningPath;
import com.learne.backend.student.learning.entity.LearningPathItem;
import com.learne.backend.student.learning.entity.LessonProgress;
import com.learne.backend.student.learning.enums.EnrollmentStatus;
import com.learne.backend.student.learning.enums.LearningPathSource;
import com.learne.backend.student.learning.enums.LearningPathStatus;
import com.learne.backend.student.learning.enums.LessonProgressStatus;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.LearningPathItemRepository;
import com.learne.backend.student.learning.repository.LearningPathRepository;
import com.learne.backend.student.learning.repository.LessonProgressRepository;
import com.learne.backend.student.learning.repository.QuestionAnswerRepository;
import com.learne.backend.student.learning.repository.QuizAttemptRepository;
import com.learne.backend.student.learning.service.StudentLearningPathService;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StudentLearningPathServiceTests {

    @Mock private CourseEnrollmentRepository courseEnrollmentRepository;
    @Mock private LessonProgressRepository lessonProgressRepository;
    @Mock private QuizAttemptRepository quizAttemptRepository;
    @Mock private QuestionAnswerRepository questionAnswerRepository;
    @Mock private LearningPathRepository learningPathRepository;
    @Mock private LearningPathItemRepository learningPathItemRepository;
    @Mock private AiLearningPathClient aiLearningPathClient;

    @Test
    void currentReturnsLatestActiveLearningPathMappedToResponse() throws Exception {
        User student = user(1L, "Student User", UserRole.STUDENT);
        User teacher = user(2L, "Teacher User", UserRole.TEACHER);
        Course course = course(10L, "English A1", teacher);
        Lesson lesson = lesson(20L, "Greetings", course);
        LearningPath path = learningPath(30L, student, "[\"grammar\",\"vocabulary\"]");
        LearningPathItem item = learningPathItem(40L, path, lesson, 1, "Start here", new BigDecimal("0.9500"), false);
        CourseEnrollment enrollment = enrollment(50L, student, course, EnrollmentStatus.ACTIVE);
        when(learningPathRepository.findFirstByUserAndStatusOrderByGeneratedAtDesc(student, LearningPathStatus.ACTIVE))
                .thenReturn(Optional.of(path));
        when(learningPathItemRepository.findByPathOrderBySequenceOrderAsc(path)).thenReturn(List.of(item));
        when(courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student)).thenReturn(List.of(enrollment));

        StudentLearningPathResponse response = service().current(student);

        assertThat(response.id()).isEqualTo(30L);
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.source()).isEqualTo("AI_SERVICE");
        assertThat(response.weaknesses()).containsExactly("grammar", "vocabulary");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().lessonId()).isEqualTo(20L);
        assertThat(response.items().getFirst().enrollmentId()).isEqualTo(50L);
        assertThat(response.items().getFirst().lessonTitle()).isEqualTo("Greetings");
        assertThat(response.items().getFirst().courseId()).isEqualTo(10L);
        assertThat(response.items().getFirst().courseTitle()).isEqualTo("English A1");
        assertThat(response.items().getFirst().teacherId()).isEqualTo(2L);
        assertThat(response.items().getFirst().teacherName()).isEqualTo("Teacher User");
        assertThat(response.items().getFirst().reason()).isEqualTo("Start here");
        assertThat(response.items().getFirst().score()).isEqualByComparingTo("0.9500");
        assertThat(response.items().getFirst().completed()).isFalse();
    }

    @Test
    void currentMarksItemCompletedWhenLessonProgressIsCompleted() throws Exception {
        User student = user(1L, "Student User", UserRole.STUDENT);
        User teacher = user(2L, "Teacher User", UserRole.TEACHER);
        Course course = course(10L, "English A1", teacher);
        Lesson lesson = lesson(20L, "Greetings", course);
        LearningPath path = learningPath(30L, student, "[]");
        LearningPathItem item = learningPathItem(40L, path, lesson, 1, "Start here", new BigDecimal("0.9500"), false);
        CourseEnrollment enrollment = enrollment(50L, student, course, EnrollmentStatus.ACTIVE);
        enrollment.getLessonProgress().add(lessonProgress(enrollment, lesson, LessonProgressStatus.COMPLETED));
        when(learningPathRepository.findFirstByUserAndStatusOrderByGeneratedAtDesc(student, LearningPathStatus.ACTIVE))
                .thenReturn(Optional.of(path));
        when(learningPathItemRepository.findByPathOrderBySequenceOrderAsc(path)).thenReturn(List.of(item));
        when(courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student)).thenReturn(List.of(enrollment));

        StudentLearningPathResponse response = service().current(student);

        assertThat(response.items().getFirst().completed()).isTrue();
    }

    @Test
    void currentParsesInvalidWeaknessJsonAsEmptyList() throws Exception {
        User student = user(1L, "Student User", UserRole.STUDENT);
        LearningPath path = learningPath(30L, student, "not-json");
        when(learningPathRepository.findFirstByUserAndStatusOrderByGeneratedAtDesc(student, LearningPathStatus.ACTIVE))
                .thenReturn(Optional.of(path));
        when(learningPathItemRepository.findByPathOrderBySequenceOrderAsc(path)).thenReturn(List.of());

        assertThat(service().current(student).weaknesses()).isEmpty();
    }

    @Test
    void nextLessonReturnsFirstIncompletePathItem() throws Exception {
        User student = user(1L, "Student User", UserRole.STUDENT);
        User teacher = user(2L, "Teacher User", UserRole.TEACHER);
        Course course = course(10L, "English A1", teacher);
        LearningPath path = learningPath(30L, student, "[]");
        LearningPathItem completed = learningPathItem(40L, path, lesson(20L, "Done", course), 1, "Done", new BigDecimal("0.1000"), true);
        LearningPathItem next = learningPathItem(41L, path, lesson(21L, "Next", course), 2, "Practice this", new BigDecimal("0.9000"), false);
        when(learningPathRepository.findFirstByUserAndStatusOrderByGeneratedAtDesc(student, LearningPathStatus.ACTIVE))
                .thenReturn(Optional.of(path));
        when(learningPathItemRepository.findByPathOrderBySequenceOrderAsc(path)).thenReturn(List.of(completed, next));
        CourseEnrollment enrollment = enrollment(50L, student, course, EnrollmentStatus.ACTIVE);
        enrollment.getLessonProgress().add(lessonProgress(enrollment, next.getLesson(), LessonProgressStatus.UNLOCKED));
        when(courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student)).thenReturn(List.of(enrollment));

        NextLessonRecommendationResponse response = service().nextLesson(student);

        assertThat(response.lessonId()).isEqualTo(21L);
        assertThat(response.enrollmentId()).isEqualTo(50L);
        assertThat(response.lessonTitle()).isEqualTo("Next");
        assertThat(response.courseId()).isEqualTo(10L);
        assertThat(response.courseTitle()).isEqualTo("English A1");
        assertThat(response.teacherId()).isEqualTo(2L);
        assertThat(response.teacherName()).isEqualTo("Teacher User");
        assertThat(response.reason()).isEqualTo("Practice this");
        assertThat(response.score()).isEqualByComparingTo("0.9000");
        verify(courseEnrollmentRepository, never()).findByUserAndCourse(student, course);
        verify(lessonProgressRepository, never()).findByCourseEnrollmentAndLesson(enrollment, next.getLesson());
    }

    @Test
    void nextLessonSkipsLockedPathItems() throws Exception {
        User student = user(1L, "Student User", UserRole.STUDENT);
        User teacher = user(2L, "Teacher User", UserRole.TEACHER);
        Course course = course(10L, "English A1", teacher);
        Lesson lockedLesson = lesson(20L, "Locked", course);
        Lesson unlockedLesson = lesson(21L, "Unlocked", course);
        LearningPath path = learningPath(30L, student, "[]");
        LearningPathItem locked = learningPathItem(40L, path, lockedLesson, 1, "Not yet", new BigDecimal("0.8000"), false);
        LearningPathItem unlocked = learningPathItem(41L, path, unlockedLesson, 2, "Practice this", new BigDecimal("0.9000"), false);
        CourseEnrollment enrollment = enrollment(50L, student, course, EnrollmentStatus.ACTIVE);
        enrollment.getLessonProgress().add(lessonProgress(enrollment, lockedLesson, LessonProgressStatus.LOCKED));
        enrollment.getLessonProgress().add(lessonProgress(enrollment, unlockedLesson, LessonProgressStatus.UNLOCKED));
        when(learningPathRepository.findFirstByUserAndStatusOrderByGeneratedAtDesc(student, LearningPathStatus.ACTIVE))
                .thenReturn(Optional.of(path));
        when(learningPathItemRepository.findByPathOrderBySequenceOrderAsc(path)).thenReturn(List.of(locked, unlocked));
        when(courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student)).thenReturn(List.of(enrollment));

        NextLessonRecommendationResponse response = service().nextLesson(student);

        assertThat(response.lessonId()).isEqualTo(21L);
        assertThat(response.enrollmentId()).isEqualTo(50L);
    }

    @Test
    void nextLessonSkipsRecommendationsCompletedInLessonProgress() throws Exception {
        User student = user(1L, "Student User", UserRole.STUDENT);
        User teacher = user(2L, "Teacher User", UserRole.TEACHER);
        Course course = course(10L, "English A1", teacher);
        Lesson completedLesson = lesson(20L, "Already complete", course);
        Lesson nextLesson = lesson(21L, "Next", course);
        LearningPath path = learningPath(30L, student, "[]");
        LearningPathItem completedInProgress = learningPathItem(40L, path, completedLesson, 1, "Review", new BigDecimal("0.8000"), false);
        LearningPathItem next = learningPathItem(41L, path, nextLesson, 2, "Practice this", new BigDecimal("0.9000"), false);
        CourseEnrollment enrollment = enrollment(student, course, EnrollmentStatus.ACTIVE);
        enrollment.getLessonProgress().add(lessonProgress(enrollment, completedLesson, LessonProgressStatus.COMPLETED));
        enrollment.getLessonProgress().add(lessonProgress(enrollment, nextLesson, LessonProgressStatus.UNLOCKED));
        when(learningPathRepository.findFirstByUserAndStatusOrderByGeneratedAtDesc(student, LearningPathStatus.ACTIVE))
                .thenReturn(Optional.of(path));
        when(learningPathItemRepository.findByPathOrderBySequenceOrderAsc(path)).thenReturn(List.of(completedInProgress, next));
        when(courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student)).thenReturn(List.of(enrollment));

        NextLessonRecommendationResponse response = service().nextLesson(student);

        assertThat(response.lessonId()).isEqualTo(21L);
    }

    @Test
    void nextLessonThrowsNotFoundWhenNoRecommendationStillEligible() throws Exception {
        User student = user(1L, "Student User", UserRole.STUDENT);
        User teacher = user(2L, "Teacher User", UserRole.TEACHER);
        Course course = course(10L, "English A1", teacher);
        Lesson lesson = lesson(20L, "Done", course);
        LearningPath path = learningPath(30L, student, "[]");
        LearningPathItem item = learningPathItem(40L, path, lesson, 1, "Review", new BigDecimal("0.8000"), false);
        CourseEnrollment enrollment = enrollment(student, course, EnrollmentStatus.ACTIVE);
        enrollment.getLessonProgress().add(lessonProgress(enrollment, lesson, LessonProgressStatus.COMPLETED));
        when(learningPathRepository.findFirstByUserAndStatusOrderByGeneratedAtDesc(student, LearningPathStatus.ACTIVE))
                .thenReturn(Optional.of(path));
        when(learningPathItemRepository.findByPathOrderBySequenceOrderAsc(path)).thenReturn(List.of(item));
        when(courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student)).thenReturn(List.of(enrollment));

        assertThatThrownBy(() -> service().nextLesson(student))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nextLessonThrowsNotFoundWhenAllItemsCompleted() throws Exception {
        User student = user(1L, "Student User", UserRole.STUDENT);
        LearningPath path = learningPath(30L, student, "[]");
        when(learningPathRepository.findFirstByUserAndStatusOrderByGeneratedAtDesc(student, LearningPathStatus.ACTIVE))
                .thenReturn(Optional.of(path));
        when(learningPathItemRepository.findByPathOrderBySequenceOrderAsc(path)).thenReturn(List.of());

        assertThatThrownBy(() -> service().nextLesson(student))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private StudentLearningPathService service() {
        return new StudentLearningPathService(
                courseEnrollmentRepository,
                quizAttemptRepository,
                questionAnswerRepository,
                learningPathRepository,
                learningPathItemRepository,
                aiLearningPathClient,
                new ObjectMapper(),
                null);
    }

    private User user(Long id, String fullName, UserRole role) throws Exception {
        User user = new User();
        setField(user, "id", id);
        user.setEmail(fullName.toLowerCase().replace(' ', '.') + "@example.com");
        user.setFullName(fullName);
        user.setRole(role);
        return user;
    }

    private Course course(Long id, String name, User teacher) throws Exception {
        Course course = new Course();
        setField(course, "id", id);
        course.setName(name);
        course.setTeacher(teacher);
        course.setStatus(ContentStatus.APPROVED);
        return course;
    }

    private Lesson lesson(Long id, String title, Course course) throws Exception {
        Topic topic = new Topic();
        topic.setCourse(course);
        Lesson lesson = new Lesson();
        setField(lesson, "id", id);
        lesson.setTitle(title);
        lesson.setTopic(topic);
        lesson.setStatus(ContentStatus.APPROVED);
        return lesson;
    }

    private CourseEnrollment enrollment(User student, Course course, EnrollmentStatus status) throws Exception {
        return enrollment(null, student, course, status);
    }

    private CourseEnrollment enrollment(Long id, User student, Course course, EnrollmentStatus status) throws Exception {
        CourseEnrollment enrollment = new CourseEnrollment();
        if (id != null) {
            setField(enrollment, "id", id);
        }
        enrollment.setUser(student);
        enrollment.setCourse(course);
        enrollment.setStatus(status);
        return enrollment;
    }

    private LessonProgress lessonProgress(CourseEnrollment enrollment, Lesson lesson, LessonProgressStatus status) {
        LessonProgress progress = new LessonProgress();
        progress.setUser(enrollment.getUser());
        progress.setCourseEnrollment(enrollment);
        progress.setLesson(lesson);
        progress.setStatus(status);
        return progress;
    }

    private LearningPath learningPath(Long id, User student, String weaknessesJson) throws Exception {
        LearningPath path = new LearningPath();
        setField(path, "id", id);
        path.setUser(student);
        path.setStatus(LearningPathStatus.ACTIVE);
        path.setSource(LearningPathSource.AI_SERVICE);
        path.setGeneratedAt(Instant.parse("2026-01-01T00:00:00Z"));
        path.setWeaknessesJson(weaknessesJson);
        return path;
    }

    private LearningPathItem learningPathItem(Long id, LearningPath path, Lesson lesson, int sequenceOrder, String reason,
            BigDecimal score, boolean completed) throws Exception {
        LearningPathItem item = new LearningPathItem();
        setField(item, "id", id);
        item.setPath(path);
        item.setLesson(lesson);
        item.setSequenceOrder(sequenceOrder);
        item.setReason(reason);
        item.setScore(score);
        item.setCompleted(completed);
        return item;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
