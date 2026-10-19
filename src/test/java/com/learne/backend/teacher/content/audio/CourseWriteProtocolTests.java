package com.learne.backend.teacher.content.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.learne.backend.admin.content.service.AdminContentReviewService;
import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.enrichment.CourseReviewEnrichmentService;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.QuestionRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
import com.learne.backend.teacher.content.repository.TopicRevisionRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.LessonRevisionRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.content.repository.QuestionRevisionRepository;
import com.learne.backend.teacher.content.service.CourseWriteLockService;
import com.learne.backend.teacher.content.service.TeacherContentService;
import com.learne.backend.teacher.content.dto.CourseRequest;
import com.learne.backend.teacher.content.service.RevisionAudioRebuildGuard;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpStatus;

/** Call-order contracts only; these tests do not simulate database concurrency. */
@ExtendWith(MockitoExtension.class)
@SpringBootTest
@ActiveProfiles("test")
class CourseWriteProtocolTests {
    @Mock CourseRepository courses;
    @Mock CourseRevisionRepository revisions;
    @Mock TopicRepository topics;
    @Mock LessonRepository lessons;
    @Mock QuestionRepository questions;
    @Mock TopicRevisionRepository topicRevisions;
    @Mock LessonRevisionRepository lessonRevisions;
    @Mock QuestionRevisionRepository questionRevisions;
    @Mock EntityManager entityManager;
    @Mock CacheInvalidationService caches;
    @Mock CourseReviewEnrichmentService enrichment;
    private CourseWriteLockService locks;
    private TeacherContentService teacherService;
    private AdminContentReviewService adminService;

    @Autowired CourseWriteLockService actualLocks;
    @Autowired EntityManager actualEntityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UserRepository actualUsers;
    @Autowired CourseRepository actualCourses;

    @BeforeEach
    void configureLockHelper() {
        locks = spy(new CourseWriteLockService(entityManager, courses));
        var submissionGuard = org.mockito.Mockito.mock(com.learne.backend.teacher.content.submission.SubmissionGuard.class);
        teacherService = new TeacherContentService(courses, revisions, topics, lessons, questions,
                topicRevisions, lessonRevisions, questionRevisions, caches, null, locks,
                new RevisionAudioRebuildGuard(), submissionGuard,
                org.mockito.Mockito.mock(com.learne.backend.teacher.content.submission.SubmissionRepository.class));
        adminService = new AdminContentReviewService(courses, revisions, entityManager, caches, locks,
                org.mockito.Mockito.mock(com.learne.backend.admin.content.service.ReviewDecisionService.class));
    }

    private final User teacher = user(7L);
    private final User admin = user(8L);

    @Test
    void unsafeAudioStopsBeforeMutatingManagedCourseOrSaving() {
        Course course = course();
        course.setName("Original");
        CourseRevision revision = pendingRevision();
        revision.setCourse(course);
        revision.setStatus(CourseRevisionStatus.DRAFT);
        revision.getTopics().getFirst().getLessons().getFirst().setAudioUrl("revision-only");
        when(courses.findLockedByIdAndTeacher(10L, teacher)).thenReturn(Optional.of(course));
        when(revisions.findTopByCourseOrderByRevisionNumberDesc(course)).thenReturn(Optional.of(revision));

        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> teacherService.updateCourse(teacher, 10L,
                new CourseRequest("Changed", "Changed", CourseType.COMPREHENSIVE, "A2", 9,
                        CoursePricingType.FREE, null))).getStatus());

        assertEquals("Original", course.getName());
        verify(courses, never()).save(any());
        verify(revisions, never()).save(any());
        verifyNoInteractions(caches);
        InOrder order = inOrder(locks, revisions);
        order.verify(locks).teacherCourse(10L, teacher);
        order.verify(revisions, times(2)).findTopByCourseOrderByRevisionNumberDesc(course);
    }

    @Test
    void deleteLessonLocksCourseBeforeLoadingGraph() {
        Course course = course();
        Topic topic = new Topic();
        topic.setCourse(course);
        Lesson lesson = new Lesson();
        ReflectionTestUtils.setField(lesson, "id", 30L);
        lesson.setTeacher(teacher);
        lesson.setTopic(topic);
        when(lessons.findById(30L)).thenReturn(Optional.of(lesson));
        when(lessons.findCourseIdByLessonIdAndTeacherId(30L, 7L)).thenReturn(Optional.of(10L));
        when(courses.findLockedByIdAndTeacher(10L, teacher)).thenReturn(Optional.of(course));

        teacherService.deleteLesson(teacher, 30L);

        InOrder order = inOrder(courses, lessons, locks, entityManager);
        order.verify(lessons).findCourseIdByLessonIdAndTeacherId(30L, 7L);
        order.verify(locks).teacherCourse(10L, teacher);
        order.verify(entityManager).clear();
        order.verify(courses).findLockedByIdAndTeacher(10L, teacher);
        order.verify(lessons).findById(30L);
        order.verify(lessons).delete(lesson);
        verifyNoInteractions(lessonRevisions);
    }

    @ParameterizedTest(name = "admin revisionPath={0}, approve={1}")
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void adminReviewLocksCourseBeforeRevision(boolean revisionPath, boolean approve) {
        CourseRevision revision = pendingRevision();
        if (revisionPath) {
            when(revisions.findLockedById(20L)).thenReturn(Optional.of(revision));
            when(revisions.findCourseIdByRevisionId(20L)).thenReturn(Optional.of(10L));
        } else {
            when(revisions.findLockedTopByCourseIdAndStatusOrderByRevisionNumberDesc(
                    10L, CourseRevisionStatus.PENDING_REVIEW)).thenReturn(Optional.of(revision));
        }
        when(courses.findLockedById(10L)).thenReturn(Optional.of(revision.getCourse()));
        when(revisions.findLockedLatestByCourseId(eq(10L), any())).thenReturn(List.of(revision));
        if (approve) {
            when(courses.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        } else {
            when(courses.save(any())).thenAnswer(call -> call.getArgument(0));
        }

        if (revisionPath) {
            if (approve) adminService.approveRevision(admin, 20L);
            else adminService.rejectRevision(admin, 20L, "Needs work");
        } else {
            if (approve) adminService.approveCourse(admin, 10L);
            else adminService.rejectCourse(admin, 10L, "Needs work");
        }

        InOrder order = inOrder(courses, revisions, locks, entityManager);
        if (revisionPath) order.verify(revisions).findCourseIdByRevisionId(20L);
        order.verify(locks).adminCourse(10L);
        order.verify(entityManager).clear();
        order.verify(courses).findLockedById(10L);
        if (revisionPath) order.verify(revisions).findLockedById(20L);
        else order.verify(revisions).findLockedTopByCourseIdAndStatusOrderByRevisionNumberDesc(
                10L, CourseRevisionStatus.PENDING_REVIEW);
        order.verify(revisions).findLockedLatestByCourseId(eq(10L), any());
        verify(locks, times(1)).adminCourse(10L);
    }

    @Test
    void helperRequiresAnExistingTransactionThroughRealSpringProxy() {
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(actualLocks));
        assertThrows(IllegalTransactionStateException.class, () -> actualLocks.teacherCourse(10L, teacher));
        assertThrows(IllegalTransactionStateException.class, () -> actualLocks.adminCourse(10L));
    }

    @Test
    void helperClearsPreloadedStateAndReloadsBeforeLocking() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User owner = new User();
            owner.setEmail("protocol-" + UUID.randomUUID() + "@example.com");
            owner.setFullName("Protocol teacher");
            owner.setPasswordHash("unused");
            owner.setRole(UserRole.TEACHER);
            owner = actualUsers.saveAndFlush(owner);
            Course original = new Course();
            original.setTeacher(owner);
            original.setName("Persisted name");
            original.setType(CourseType.COMPREHENSIVE);
            original.setLevel("A2");
            original.setStatus(ContentStatus.DRAFT);
            original = actualCourses.saveAndFlush(original);
            original.setName("Unflushed preloaded state");

            Course locked = actualLocks.teacherCourse(original.getId(), owner);

            assertFalse(actualEntityManager.contains(original));
            assertTrue(actualEntityManager.contains(locked));
            assertNotSame(original, locked);
            assertEquals("Persisted name", locked.getName());
            Course adminLocked = actualLocks.adminCourse(locked.getId());
            assertNotSame(locked, adminLocked);
            assertFalse(actualEntityManager.contains(locked));
            assertEquals("Persisted name", adminLocked.getName());
            status.setRollbackOnly();
        });
    }

    @Test
    void revisionLessonReloadOccursAfterCourseLockAndBeforeRevisionLock() {
        CourseRevision revision = pendingRevision();
        revision.setStatus(CourseRevisionStatus.DRAFT);
        LessonRevision lesson = revision.getTopics().getFirst().getLessons().getFirst();
        when(lessons.findCourseIdByLessonIdAndTeacherId(30L, 7L)).thenReturn(Optional.empty());
        when(lessonRevisions.findCourseIdByLessonIdAndTeacherId(30L, 7L)).thenReturn(Optional.of(10L));
        when(courses.findLockedByIdAndTeacher(10L, teacher)).thenReturn(Optional.of(revision.getCourse()));
        when(lessonRevisions.findEditableLatestForTeacher(7L, 30L)).thenReturn(Optional.of(lesson));
        when(revisions.findLockedLatestByCourseId(eq(10L), any())).thenReturn(List.of(revision));

        teacherService.deleteLesson(teacher, 30L);

        InOrder order = inOrder(lessons, lessonRevisions, locks, revisions);
        order.verify(lessons).findCourseIdByLessonIdAndTeacherId(30L, 7L);
        order.verify(lessonRevisions).findCourseIdByLessonIdAndTeacherId(30L, 7L);
        order.verify(locks).teacherCourse(10L, teacher);
        order.verify(lessonRevisions).findEditableLatestForTeacher(7L, 30L);
        order.verify(revisions).findLockedLatestByCourseId(eq(10L), any());
        verify(lessons, never()).findById(anyLong());
    }

    @Test
    void disappearingLiveCandidateDoesNotSwitchToRevisionNamespace() {
        when(lessons.findCourseIdByLessonIdAndTeacherId(30L, 7L)).thenReturn(Optional.of(10L));
        when(courses.findLockedByIdAndTeacher(10L, teacher)).thenReturn(Optional.of(course()));
        when(lessons.findById(30L)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> teacherService.deleteLesson(teacher, 30L)).getStatus());

        verifyNoInteractions(lessonRevisions);
        verify(lessons, never()).delete(any());
    }

    @Test
    void ineligibleRevisionReloadDoesNotFallBackToLiveGraph() {
        when(lessonRevisions.findCourseIdByLessonIdAndTeacherId(30L, 7L)).thenReturn(Optional.of(10L));
        when(courses.findLockedByIdAndTeacher(10L, teacher)).thenReturn(Optional.of(course()));
        when(lessonRevisions.findEditableLatestForTeacher(7L, 30L)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> teacherService.deleteLesson(teacher, 30L)).getStatus());

        InOrder order = inOrder(lessonRevisions, locks);
        order.verify(lessonRevisions).findCourseIdByLessonIdAndTeacherId(30L, 7L);
        order.verify(locks).teacherCourse(10L, teacher);
        order.verify(lessonRevisions).findEditableLatestForTeacher(7L, 30L);
        verify(lessons, never()).findById(anyLong());
    }

    @Test
    void missingOrForeignOwnerStopsBeforeLockOrGraphLookup() {
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> teacherService.deleteLesson(teacher, 30L)).getStatus());
        verifyNoInteractions(locks);
        verify(lessons, never()).findById(anyLong());
        verify(lessonRevisions, never()).findEditableLatestForTeacher(anyLong(), anyLong());
    }

    @Test
    void syncDoesNotMutateAnOlderDraftBehindNewerPendingRevision() {
        Course course = course();
        Topic topic = new Topic();
        topic.setCourse(course);
        Lesson lesson = new Lesson();
        lesson.setTeacher(teacher);
        lesson.setTopic(topic);
        CourseRevision latest = pendingRevision();
        when(lessons.findCourseIdByLessonIdAndTeacherId(30L, 7L)).thenReturn(Optional.of(10L));
        when(courses.findLockedByIdAndTeacher(10L, teacher)).thenReturn(Optional.of(course));
        when(lessons.findById(30L)).thenReturn(Optional.of(lesson));
        when(revisions.findTopByCourseOrderByRevisionNumberDesc(course)).thenReturn(Optional.of(latest));

        teacherService.deleteLesson(teacher, 30L);

        verify(revisions, never()).findTopByCourseAndStatusOrderByRevisionNumberDesc(any(), any());
        verify(revisions, never()).save(any());
    }

    private Course course() {
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", 10L);
        course.setTeacher(teacher);
        course.setStatus(ContentStatus.DRAFT);
        return course;
    }

    private CourseRevision pendingRevision() {
        CourseRevision revision = new CourseRevision();
        ReflectionTestUtils.setField(revision, "id", 20L);
        revision.setRevisionNumber(1);
        revision.setCourse(course());
        revision.setTeacher(teacher);
        revision.setStatus(CourseRevisionStatus.PENDING_REVIEW);
        revision.setPricingType(CoursePricingType.FREE);
        TopicRevision topic = new TopicRevision();
        topic.setCourseRevision(revision);
        LessonRevision lesson = new LessonRevision();
        lesson.setTopicRevision(topic);
        lesson.setTeacher(teacher);
        QuestionRevision question = new QuestionRevision();
        question.setLessonRevision(lesson);
        question.setTeacher(teacher);
        lesson.getQuestions().add(question);
        topic.getLessons().add(lesson);
        revision.getTopics().add(topic);
        return revision;
    }

    private static User user(Long id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
