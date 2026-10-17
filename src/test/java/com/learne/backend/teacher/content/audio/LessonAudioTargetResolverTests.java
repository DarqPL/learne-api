package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.audio.dto.AudioTargetKind;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.audio.dto.LessonAudioToken;
import com.learne.backend.teacher.content.audio.service.LessonAudioReadService;
import com.learne.backend.teacher.content.audio.service.LessonAudioTargetResolver;
import com.learne.backend.teacher.content.entity.*;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.repository.*;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.aop.framework.ProxyFactory;

class LessonAudioTargetResolverTests {
    private final LessonRepository lessons = mock(LessonRepository.class);
    private final LessonRevisionRepository revisionLessons = mock(LessonRevisionRepository.class);
    private final CourseRevisionRepository revisions = mock(CourseRevisionRepository.class);
    private final LessonAudioTargetResolver resolver = new LessonAudioTargetResolver(lessons, revisionLessons, revisions);
    private final User teacher = identified(new User(), 1L);
    private final User foreign = identified(new User(), 2L);
    private final Course course = identified(new Course(), 10L);
    private final CourseRevision revision = identified(new CourseRevision(), 20L);
    private final Lesson live = identified(new Lesson(), 30L);
    private final LessonRevision counterpart = identified(new LessonRevision(), 40L);

    @BeforeEach
    void graph() {
        course.setTeacher(teacher);
        course.setStatus(ContentStatus.DRAFT);
        revision.setCourse(course);
        revision.setTeacher(teacher);
        revision.setStatus(CourseRevisionStatus.DRAFT);
        Topic topic = identified(new Topic(), 11L);
        topic.setCourse(course);
        course.getTopics().add(topic);
        live.setTopic(topic);
        live.setTeacher(teacher);
        live.setStatus(ContentStatus.DRAFT);
        topic.getLessons().add(live);
        TopicRevision draftTopic = identified(new TopicRevision(), 21L);
        draftTopic.setCourseRevision(revision);
        revision.getTopics().add(draftTopic);
        counterpart.setTopicRevision(draftTopic);
        counterpart.setTeacher(teacher);
        counterpart.setSourceLesson(live);
        draftTopic.getLessons().add(counterpart);
        when(lessons.findById(30L)).thenReturn(Optional.of(live));
        when(revisionLessons.findById(40L)).thenReturn(Optional.of(counterpart));
        when(revisions.findTopByCourseIdOrderByRevisionNumberDesc(10L)).thenReturn(Optional.of(revision));
    }

    @Test
    void liveCollisionUsesOnlyLiveNamespaceAndExplicitSourceCounterpart() {
        LessonRevision unrelated = identified(new LessonRevision(), 30L);
        unrelated.setTeacher(teacher);
        unrelated.setTopicRevision(counterpart.getTopicRevision());
        when(revisionLessons.findById(30L)).thenReturn(Optional.of(unrelated));
        var target = resolver.resolve(teacher, AudioTargetKind.LIVE, 30L);
        assertThat(target.liveLesson()).isSameAs(live);
        assertThat(target.revisionLesson()).isSameAs(counterpart);
        assertThat(target.course()).isSameAs(course);
        assertThat(target.revision()).isSameAs(revision);
        assertThat(target.token()).isEqualTo(new LessonAudioToken(AudioTargetKind.LIVE, 30L, 10L,
                20L, LessonAudioState.empty(), 40L, LessonAudioState.empty()));
        verifyNoInteractions(revisionLessons);
    }

    @Test
    void revisionCollisionUsesActualSourceRatherThanSameNumericLiveId() {
        Lesson unrelated = identified(new Lesson(), 40L);
        unrelated.setTeacher(teacher);
        unrelated.setTopic(live.getTopic());
        when(lessons.findById(40L)).thenReturn(Optional.of(unrelated));
        var target = resolver.resolve(teacher, AudioTargetKind.REVISION, 40L);
        assertThat(target.liveLesson()).isSameAs(live);
        assertThat(target.token()).isEqualTo(new LessonAudioToken(AudioTargetKind.REVISION, 40L, 10L,
                20L, LessonAudioState.empty(), 30L, LessonAudioState.empty()));
        verifyNoInteractions(lessons);
    }

    @ParameterizedTest
    @EnumSource(AudioTargetKind.class)
    void missingTypedIdNeverFallsBack(AudioTargetKind kind) {
        if (kind == AudioTargetKind.LIVE) when(revisionLessons.findById(999L)).thenReturn(Optional.of(counterpart));
        else when(lessons.findById(999L)).thenReturn(Optional.of(live));
        rejects(kind, 999L, HttpStatus.NOT_FOUND);
        verifyNoInteractions(revisions);
        if (kind == AudioTargetKind.LIVE) verifyNoInteractions(revisionLessons);
        else verifyNoInteractions(lessons);
    }

    @ParameterizedTest
    @ValueSource(strings = {"live", "course-live", "revision-lesson", "course-revision", "revision"})
    void foreignOwnershipIs404BeforeStatusChecks(String owner) {
        revision.setStatus(CourseRevisionStatus.PENDING_REVIEW);
        live.setStatus(ContentStatus.APPROVED);
        AudioTargetKind kind = AudioTargetKind.REVISION;
        long id = 40L;
        switch (owner) {
            case "live" -> { live.setTeacher(foreign); kind = AudioTargetKind.LIVE; id = 30L; }
            case "course-live" -> { course.setTeacher(foreign); kind = AudioTargetKind.LIVE; id = 30L; }
            case "revision-lesson" -> counterpart.setTeacher(foreign);
            case "course-revision" -> course.setTeacher(foreign);
            case "revision" -> revision.setTeacher(foreign);
            default -> throw new AssertionError(owner);
        }
        rejects(kind, id, HttpStatus.NOT_FOUND);
        verifyNoInteractions(revisions);
    }

    @ParameterizedTest
    @EnumSource(value = CourseRevisionStatus.class, names = {"PENDING_REVIEW", "APPROVED", "SUPERSEDED"})
    void ownedNoneditableLatestIs409InBothNamespaces(CourseRevisionStatus status) {
        revision.setStatus(status);
        rejects(AudioTargetKind.LIVE, 30L, HttpStatus.CONFLICT);
        rejects(AudioTargetKind.REVISION, 40L, HttpStatus.CONFLICT);
    }

    @Test
    void staleRevisionCannotUseOlderEditableStatus() {
        CourseRevision newer = identified(new CourseRevision(), 22L);
        newer.setStatus(CourseRevisionStatus.PENDING_REVIEW);
        when(revisions.findTopByCourseIdOrderByRevisionNumberDesc(10L)).thenReturn(Optional.of(newer));
        rejects(AudioTargetKind.REVISION, 40L, HttpStatus.CONFLICT);
    }

    @ParameterizedTest
    @EnumSource(AudioTargetKind.class)
    void missingLatestIs409(AudioTargetKind kind) {
        when(revisions.findTopByCourseIdOrderByRevisionNumberDesc(10L)).thenReturn(Optional.empty());
        rejects(kind, kind == AudioTargetKind.LIVE ? 30L : 40L, HttpStatus.CONFLICT);
    }

    @ParameterizedTest
    @EnumSource(value = CourseRevisionStatus.class, names = {"DRAFT", "REJECTED"})
    void latestUnpublishedRevisionIsEditable(CourseRevisionStatus status) {
        revision.setStatus(status);
        assertThat(resolver.resolve(teacher, AudioTargetKind.REVISION, 40L).token().revisionId()).isEqualTo(20L);
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatus.class, names = {"PENDING_REVIEW", "APPROVED", "HIDDEN"})
    void liveRequiresEditableLessonStatus(ContentStatus status) {
        live.setStatus(status);
        rejects(AudioTargetKind.LIVE, 30L, HttpStatus.CONFLICT);
    }

    @Test
    void rejectedLiveIsEditable() {
        live.setStatus(ContentStatus.REJECTED);
        assertThat(resolver.resolve(teacher, AudioTargetKind.LIVE, 30L).kind()).isEqualTo(AudioTargetKind.LIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"timestamp", "approved"})
    void publishedCourseRejectsLiveButRevisionHasNoMirror(String publication) {
        if (publication.equals("timestamp")) course.setPublishedAt(Instant.now());
        else course.setStatus(ContentStatus.APPROVED);
        rejects(AudioTargetKind.LIVE, 30L, HttpStatus.CONFLICT);
        var target = resolver.resolve(teacher, AudioTargetKind.REVISION, 40L);
        assertThat(counterpart.getSourceLesson()).isSameAs(live);
        assertThat(target.liveLesson()).isNull();
        assertThat(target.token().mirrorId()).isNull();
        assertThat(target.token().mirrorState()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void liveRequiresExactlyOneSourceLinkedMirror(int count) {
        if (count == 0) counterpart.setSourceLesson(null);
        else {
            LessonRevision duplicate = identified(new LessonRevision(), 41L);
            duplicate.setSourceLesson(live);
            duplicate.setTopicRevision(counterpart.getTopicRevision());
            duplicate.setTeacher(teacher);
            counterpart.getTopicRevision().getLessons().add(duplicate);
        }
        rejects(AudioTargetKind.LIVE, 30L, HttpStatus.CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"url", "publicId", "partial"})
    void liveRejectsMismatchButRevisionCanRepairIt(String mismatch) {
        live.setAudioUrl("url");
        live.setAudioPublicId("publicId");
        counterpart.setAudioUrl(mismatch.equals("url") ? "other" : "url");
        counterpart.setAudioPublicId(mismatch.equals("publicId") ? "other" : mismatch.equals("partial") ? null : "publicId");
        rejects(AudioTargetKind.LIVE, 30L, HttpStatus.CONFLICT);
        var token = resolver.resolve(teacher, AudioTargetKind.REVISION, 40L).token();
        assertThat(token.state()).isEqualTo(LessonAudioState.from(counterpart));
        assertThat(token.mirrorState()).isEqualTo(new LessonAudioState("url", "publicId"));
    }

    @Test
    void nullAudioWithRealMirrorDiffersFromAbsentMirrorAndSnapshotIsImmutable() {
        var mirrored = resolver.resolve(teacher, AudioTargetKind.REVISION, 40L).token();
        counterpart.setSourceLesson(null);
        var standalone = resolver.resolve(teacher, AudioTargetKind.REVISION, 40L).token();
        assertThat(standalone.mirrorId()).isNull();
        assertThat(standalone.mirrorState()).isNull();
        assertThat(mirrored).isNotEqualTo(standalone);
        counterpart.setAudioUrl("changed");
        live.setAudioPublicId("changed");
        assertThat(mirrored.state()).isEqualTo(LessonAudioState.empty());
        assertThat(mirrored.mirrorState()).isEqualTo(LessonAudioState.empty());
    }

    @Test
    void exactNonemptyTokenUsesValueEqualityIncludingApprovedAbaLimitation() {
        live.setAudioUrl("url");
        live.setAudioPublicId("publicId");
        counterpart.setAudioUrl("url");
        counterpart.setAudioPublicId("publicId");
        var original = resolver.resolve(teacher, AudioTargetKind.LIVE, 30L).token();
        assertThat(original).isEqualTo(new LessonAudioToken(AudioTargetKind.LIVE, 30L, 10L, 20L,
                new LessonAudioState("url", "publicId"), 40L, new LessonAudioState("url", "publicId")));
        live.setAudioUrl("changed");
        counterpart.setAudioUrl("changed");
        assertThat(resolver.resolve(teacher, AudioTargetKind.LIVE, 30L).token()).isNotEqualTo(original);
        live.setAudioUrl("url");
        counterpart.setAudioUrl("url");
        assertThat(resolver.resolve(teacher, AudioTargetKind.LIVE, 30L).token()).isEqualTo(original);
    }

    @Test
    void publishedLiveLessonIs409EvenInUnpublishedCourse() {
        live.setPublishedAt(Instant.now());
        rejects(AudioTargetKind.LIVE, 30L, HttpStatus.CONFLICT);
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatus.class, names = {"PENDING_REVIEW", "HIDDEN"})
    void liveRequiresEditableCourseStatus(ContentStatus status) {
        course.setStatus(status);
        rejects(AudioTargetKind.LIVE, 30L, HttpStatus.CONFLICT);
    }

    @Test
    void sourceLessRevisionIgnoresOwnedLiveNumericCollision() {
        counterpart.setSourceLesson(null);
        when(lessons.findById(40L)).thenReturn(Optional.of(live));
        var target = resolver.resolve(teacher, AudioTargetKind.REVISION, 40L);
        assertThat(target.liveLesson()).isNull();
        assertThat(target.token().mirrorId()).isNull();
        verifyNoInteractions(lessons);
    }

    @ParameterizedTest
    @ValueSource(strings = {"teacher", "course"})
    void maliciousSourceIsNotMirrored(String violation) {
        if (violation.equals("teacher")) live.setTeacher(foreign);
        else live.getTopic().setCourse(identified(new Course(), 99L));
        rejects(AudioTargetKind.REVISION, 40L, HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"teacher", "revision-teacher", "topic-parent", "lesson-parent", "latest-course"})
    void liveRejectsInvalidMirrorGraph(String violation) {
        switch (violation) {
            case "teacher" -> counterpart.setTeacher(foreign);
            case "revision-teacher" -> revision.setTeacher(foreign);
            case "topic-parent" -> counterpart.getTopicRevision().setCourseRevision(identified(new CourseRevision(), 99L));
            case "lesson-parent" -> counterpart.setTopicRevision(new TopicRevision());
            case "latest-course" -> revision.setCourse(identified(new Course(), 99L));
            default -> throw new AssertionError(violation);
        }
        rejects(AudioTargetKind.LIVE, 30L, HttpStatus.CONFLICT);
    }

    @Test
    void preflightProxyUsesIndependentReadOnlyRepeatableReadAndReturnsOnlyToken() {
        LessonAudioTargetResolver checkingResolver = mock(LessonAudioTargetResolver.class);
        when(checkingResolver.resolve(teacher, AudioTargetKind.LIVE, 30L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return resolver.resolve(teacher, AudioTargetKind.LIVE, 30L);
        });
        var attributes = new AnnotationTransactionAttributeSource();
        var attribute = attributes.getTransactionAttribute(
                org.springframework.util.ReflectionUtils.findMethod(LessonAudioReadService.class, "preflight", User.class, AudioTargetKind.class, Long.class),
                LessonAudioReadService.class);
        assertThat(attribute).isNotNull();
        assertThat(attribute.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AbstractPlatformTransactionManager manager = new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) { }
        };
        ProxyFactory factory = new ProxyFactory(new LessonAudioReadService(checkingResolver, mock(CourseRepository.class), revisions));
        factory.addAdvice(new TransactionInterceptor(manager, attributes));
        var proxy = (LessonAudioReadService) factory.getProxy();
        assertThat(proxy.preflight(teacher, AudioTargetKind.LIVE, 30L))
                .isEqualTo(resolver.resolve(teacher, AudioTargetKind.LIVE, 30L).token());
        verify(checkingResolver).resolve(teacher, AudioTargetKind.LIVE, 30L);
    }

    private void rejects(AudioTargetKind kind, Long id, HttpStatus status) {
        assertThatThrownBy(() -> resolver.resolve(teacher, kind, id))
                .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getStatus()).isEqualTo(status));
    }

    private static <T> T identified(T entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
