package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.service.RevisionAudioRebuildGuard;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

class RevisionAudioRebuildGuardTests {
    private final RevisionAudioRebuildGuard guard = new RevisionAudioRebuildGuard();
    private final Course course = new Course();
    private final CourseRevision revision = new CourseRevision();

    @Test
    void rejectsMismatchedUrlOrPublicIdIncludingPartialMetadata() {
        Lesson live = liveLesson(10L);
        live.setAudioUrl("url");
        live.setAudioPublicId("id");
        LessonRevision audio = revisionLesson(live);
        audio.setAudioUrl("other");
        audio.setAudioPublicId("id");
        rejects(Set.of());
        audio.setAudioUrl("url");
        audio.setAudioPublicId(null);
        rejects(Set.of());
        audio.setAudioUrl(null);
        audio.setAudioPublicId("id");
        rejects(Set.of());
    }

    @Test
    void rejectsRevisionOnlyAudioDespiteNumericIdTitleAndOrderCollision() {
        Lesson live = liveLesson(10L);
        live.setTitle("same");
        LessonRevision audio = revisionLesson(null);
        ReflectionTestUtils.setField(audio, "id", 10L);
        audio.setTitle("same");
        audio.setAudioUrl("url");
        rejects(Set.of(10L));
    }

    @Test
    void rejectsSourceOutsideLiveCourseEvenWhenItsAudioMatches() {
        Lesson foreign = new Lesson();
        ReflectionTestUtils.setField(foreign, "id", 99L);
        foreign.setAudioPublicId("id");
        revisionLesson(foreign).setAudioPublicId("id");
        rejects(Set.of());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"url"})
    void acceptsIdenticalNullSafeAudioPair(String url) {
        Lesson live = liveLesson(10L);
        live.setAudioUrl(url);
        live.setAudioPublicId("id");
        LessonRevision audio = revisionLesson(live);
        audio.setAudioUrl(url);
        audio.setAudioPublicId("id");
        assertThatCode(() -> guard.assertSafe(course, revision, Set.of())).doesNotThrowAnyException();
    }

    @Test
    void acceptsNoAudioWithoutSource() {
        revisionLesson(null);
        assertThatCode(() -> guard.assertSafe(course, revision, Set.of())).doesNotThrowAnyException();
    }

    @Test
    void exemptsOnlyDeliberatelyDeletedSourcesAndStillRejectsBadSurvivor() {
        Lesson deleted = liveLesson(10L);
        revisionLesson(deleted).setAudioUrl("different");
        assertThatCode(() -> guard.assertSafe(course, revision, Set.of(10L))).doesNotThrowAnyException();
        revisionLesson(liveLesson(11L)).setAudioPublicId("unrelated");
        rejects(Set.of(10L));
    }

    private void rejects(Set<Long> deleted) {
        assertThatThrownBy(() -> guard.assertSafe(course, revision, deleted))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).containsIgnoringCase("audio").containsIgnoringCase("revision");
                });
    }

    private Lesson liveLesson(long id) {
        Topic topic = new Topic();
        topic.setCourse(course);
        course.getTopics().add(topic);
        Lesson lesson = new Lesson();
        ReflectionTestUtils.setField(lesson, "id", id);
        lesson.setTopic(topic);
        topic.getLessons().add(lesson);
        return lesson;
    }

    private LessonRevision revisionLesson(Lesson source) {
        revision.setCourse(course);
        TopicRevision topic = new TopicRevision();
        topic.setCourseRevision(revision);
        revision.getTopics().add(topic);
        LessonRevision lesson = new LessonRevision();
        lesson.setTopicRevision(topic);
        lesson.setSourceLesson(source);
        topic.getLessons().add(lesson);
        return lesson;
    }
}
