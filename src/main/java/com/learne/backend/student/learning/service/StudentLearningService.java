package com.learne.backend.student.learning.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.dto.StudentCourseDetailResponse;
import com.learne.backend.student.learning.dto.StudentCourseSummaryResponse;
import com.learne.backend.student.learning.dto.StudentEnrollmentProgressResponse;
import com.learne.backend.student.learning.dto.StudentEnrollmentResponse;
import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.entity.LessonProgress;
import com.learne.backend.student.learning.enums.EnrollmentStatus;
import com.learne.backend.student.learning.enums.LessonProgressStatus;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.LessonProgressRepository;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.repository.CourseRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final EntityManager entityManager;
    private final TransactionTemplate createEnrollmentTransactionTemplate;

    public StudentLearningService(CourseRepository courseRepository,
                                   CourseEnrollmentRepository courseEnrollmentRepository,
                                   LessonProgressRepository lessonProgressRepository,
                                   EntityManager entityManager,
                                   PlatformTransactionManager transactionManager) {
        this.courseRepository = courseRepository;
        this.courseEnrollmentRepository = courseEnrollmentRepository;
        this.lessonProgressRepository = lessonProgressRepository;
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

        CourseEnrollment enrollment = courseEnrollmentRepository.findByUserAndCourse(student, course)
                .orElseGet(() -> findOrCreateEnrollment(student, course));
        return StudentEnrollmentResponse.from(enrollment);
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
        return StudentCourseDetailResponse.from(course, enrollment);
    }

    @Transactional(readOnly = true)
    public StudentEnrollmentProgressResponse getEnrollmentProgress(User student, Long enrollmentId) {
        return StudentEnrollmentProgressResponse.from(findEnrollment(student, enrollmentId));
    }

    @Transactional
    public StudentEnrollmentProgressResponse startLesson(User student, Long enrollmentId, Long lessonId) {
        CourseEnrollment enrollment = findEnrollment(student, enrollmentId);
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
        LessonProgress progress = findLessonProgress(enrollment, lessonId);
        ensureLessonStillApproved(progress);
        if (progress.getStatus() == LessonProgressStatus.LOCKED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Lesson is locked.");
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
            unlockNextLesson(enrollment);
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

    private void unlockNextLesson(CourseEnrollment enrollment) {
        List<LessonProgress> ordered = orderedProgress(enrollment);
        for (int index = 0; index < ordered.size(); index++) {
            LessonProgress current = ordered.get(index);
            if (current.getStatus() == LessonProgressStatus.COMPLETED && index + 1 < ordered.size()) {
                LessonProgress next = ordered.get(index + 1);
                if (next.getStatus() == LessonProgressStatus.LOCKED) {
                    next.setStatus(LessonProgressStatus.UNLOCKED);
                    lessonProgressRepository.save(next);
                    return;
                }
            }
        }
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

    private StudentCourseSummaryResponse toCourseSummary(User student, Course course) {
        List<Lesson> approvedLessons = orderedApprovedLessons(course);
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
                courseEnrollmentRepository.existsByUserAndCourse(student, course)
        );
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
