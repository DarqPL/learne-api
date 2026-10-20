package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.learne.backend.storage.config.CloudinaryProperties;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.audio.service.LessonAudioCleanupDecisionService;
import com.learne.backend.teacher.content.audio.service.LessonAudioCleanupService;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.repository.*;
import java.util.List;
import java.util.Optional;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LessonAudioCleanupServiceTests {
    private final CourseRepository courses = mock(CourseRepository.class);
    private final LessonRepository live = mock(LessonRepository.class);
    private final LessonRevisionRepository revisions = mock(LessonRevisionRepository.class);
    private final CloudinaryProperties properties = new CloudinaryProperties();
    private final StorageService storage = mock(StorageService.class);

    private LessonAudioCleanupDecisionService decision() {
        properties.setCloudName("test-cloud");
        return new LessonAudioCleanupDecisionService(courses, live, revisions, properties);
    }

    static LessonAudioState state(String suffix) {
        return new LessonAudioState("https://res.cloudinary.com/test-cloud/video/upload/v123/learne/course-audio/"
                + suffix + ".mp3", "learne/course-audio/" + suffix);
    }

    @Test
    void unreferencedManagedAudioIsDeletedOnceAndFailuresDoNotStopOtherCandidates() {
        when(courses.findLockedById(1L)).thenReturn(Optional.of(new Course()));
        doThrow(new IllegalStateException("provider unavailable")).when(storage)
                .delete(state("first").publicId(), StorageResourceType.VIDEO);
        tick(decision(), List.of(state("first"), state("first"), state("second")));
        verify(storage).delete(state("first").publicId(), StorageResourceType.VIDEO);
        verify(storage).delete(state("second").publicId(), StorageResourceType.VIDEO);
        var order = inOrder(courses, live, revisions);
        order.verify(courses).findLockedById(1L);
        order.verify(live).existsByAudioPublicId(state("first").publicId());
        order.verify(live).existsByAudioUrl(state("first").url());
        order.verify(revisions).existsByAudioPublicId(state("first").publicId());
        order.verify(revisions).existsByAudioUrl(state("first").url());
    }

    @Test
    void everyReferenceNamespacePreventsDeletion() {
        when(courses.findLockedById(1L)).thenReturn(Optional.of(new Course()));
        var decision = decision();
        when(live.existsByAudioPublicId(state("live-id").publicId())).thenReturn(true);
        when(live.existsByAudioUrl(state("live-url").url())).thenReturn(true);
        when(revisions.existsByAudioPublicId(state("history-id").publicId())).thenReturn(true);
        when(revisions.existsByAudioUrl(state("history-url").url())).thenReturn(true);
        tick(decision, List.of(state("live-id"), state("live-url"), state("history-id"), state("history-url")));
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://res.cloudinary.com/test-cloud/video/upload/learne/course-audio/a.mp3",
            "https://res.cloudinary.com/other/video/upload/learne/course-audio/a.mp3",
            "https://custom.example/test-cloud/video/upload/learne/course-audio/a.mp3",
            "https://res.cloudinary.com/test-cloud/image/upload/learne/course-audio/a.mp3",
            "https://res.cloudinary.com/test-cloud/video/private/learne/course-audio/a.mp3",
            "https://res.cloudinary.com/test-cloud/video/upload/c_scale,w_10/learne/course-audio/a.mp3",
            "https://res.cloudinary.com/test-cloud/video/upload/learne/course-audio/other.mp3",
            "https://res.cloudinary.com/test-cloud/video/upload/learne/course-audio/a.mp3?x=1",
            "https://res.cloudinary.com/test-cloud/video/upload/learne/course-audio/a.mp3#fragment",
            "https://user@res.cloudinary.com/test-cloud/video/upload/learne/course-audio/a.mp3",
            "https://res.cloudinary.com:443/test-cloud/video/upload/learne/course-audio/a.mp3",
            "https://res.cloudinary.com/test-cloud/video/upload/learne/course-audio/%61.mp3",
            "https://res.cloudinary.com/test-cloud/video/upload/learne/course-audio/../a.mp3",
            "https://res.cloudinary.com/test-cloud/video/upload/learne/./course-audio/a.mp3",
            "https://res.cloudinary.com/test-cloud/video/upload/learne/temporary/../course-audio/a.mp3",
            "https://res.cloudinary.com/test-cloud/video/upload/learne//course-audio/a.mp3",
            "not a URI"
    })
    void unknownOrMismatchedProvenanceIsRetained(String url) {
        assertThat(decision().canDelete(1L, new LessonAudioState(url, state("a").publicId()))).isFalse();
        verifyNoInteractions(courses, live, revisions, storage);
    }

    @Test
    void missingCourseUrlOnlyAndOutsideFolderAreRetained() {
        var decision = decision();
        assertThat(decision.canDelete(null, state("a"))).isFalse();
        assertThat(decision.canDelete(1L, state("a"))).isFalse();
        assertThat(decision.canDelete(1L, new LessonAudioState(state("a").url(), null))).isFalse();
        assertThat(decision.canDelete(1L, new LessonAudioState(state("a").url(), "learne/course-audio-other/a"))).isFalse();
        assertThat(decision.canDelete(1L, state("../a"))).isFalse();
        assertThat(decision.canDelete(1L, new LessonAudioState(null, state("a").publicId()))).isFalse();
        assertThat(decision.canDelete(1L, state(""))).isFalse();
        properties.setCloudName("");
        assertThat(decision.canDelete(1L, state("a"))).isFalse();
    }

    @Test
    void decisionFailureNeverDeletesAndContinues() {
        var decision = mock(LessonAudioCleanupDecisionService.class);
        when(decision.evaluate(1L, state("a"))).thenThrow(new IllegalStateException("database unavailable"));
        when(decision.evaluate(1L, state("b"))).thenReturn(new com.learne.backend.teacher.content.audio.dto.CleanupDecision(com.learne.backend.teacher.content.audio.dto.CleanupDecision.Kind.DELETE,"SAFE_ABSENCE"));
        tick(decision, List.of(state("a"), state("b")));
        verify(storage, never()).delete(state("a").publicId(), StorageResourceType.VIDEO);
        verify(storage).delete(state("b").publicId(), StorageResourceType.VIDEO);
    }

    @Test
    void queryFailureIsAConservativeDecision() {
        when(courses.findLockedById(1L)).thenReturn(Optional.of(new Course()));
        when(live.existsByAudioPublicId(any())).thenThrow(new IllegalStateException("query failed"));
        assertThat(decision().canDelete(1L, state("a"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "v123/"})
    void canonicalUrlAcceptsOptionalVersionAndExtension(String version) {
        when(courses.findLockedById(1L)).thenReturn(Optional.of(new Course()));
        for (String extension : List.of("", ".mp3", ".wav", ".m4a", ".ogg")) {
            assertThat(decision().canDelete(1L, new LessonAudioState(
                    "https://res.cloudinary.com/test-cloud/video/upload/" + version + "learne/course-audio/a" + extension,
                    state("a").publicId()))).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"provenance", "reference", "query", "missing-course"})
    void retentionLogsIdentifyCandidateAndTargetWithoutSensitiveDetails(String reason) {
        LessonAudioState candidate = state("retained");
        if (!reason.equals("missing-course")) {
            when(courses.findLockedById(1L)).thenReturn(Optional.of(new Course()));
        }
        if (reason.equals("reference")) {
            when(live.existsByAudioPublicId(candidate.publicId())).thenReturn(true);
        } else if (reason.equals("query")) {
            when(live.existsByAudioPublicId(any())).thenThrow(new IllegalStateException(
                    "provider-credential synthetic-binary " + candidate.url()));
        } else if (reason.equals("provenance")) {
            candidate = new LessonAudioState("https://user:provider-credential@custom.example/private", candidate.publicId());
        }
        LessonAudioState input = candidate;
        var events = captureLogs(LessonAudioCleanupDecisionService.class,
                () -> assertThat(decision().canDelete(1L, input)).isFalse());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getFormattedMessage()).contains("publicId=" + candidate.publicId(), "course 1", "VIDEO")
                .doesNotContain(candidate.url(), "provider-credential", "synthetic-binary");
        assertThat(events.getFirst().getThrowableProxy()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"decision", "provider"})
    void failureLogsIdentifyCandidateAndTargetWithoutProviderMessages(String failure) {
        var decision = mock(LessonAudioCleanupDecisionService.class);
        LessonAudioState candidate = state("failed");
        RuntimeException unavailable = new IllegalStateException("provider-credential synthetic-binary " + candidate.url());
        if (failure.equals("decision")) {
            when(decision.canDelete(1L, candidate)).thenThrow(unavailable);
        } else {
            when(decision.canDelete(1L, candidate)).thenReturn(true);
            doThrow(unavailable).when(storage).delete(candidate.publicId(), StorageResourceType.VIDEO);
        }
        var compensation = mock(com.learne.backend.teacher.content.audio.service.MediaCompensationService.class);
        doThrow(unavailable).when(compensation).enqueueConfirmedRollback(any(),eq(1L),anyList(),eq(false),isNull());
        var events = captureLogs(LessonAudioCleanupService.class,
                () -> new LessonAudioCleanupService(compensation).compensate(java.util.UUID.randomUUID(),1L,List.of(candidate),false,null));
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getFormattedMessage()).contains("publicId=" + candidate.publicId(), "course 1", "VIDEO",
                        "IllegalStateException")
                .doesNotContain(candidate.url(), "provider-credential", "synthetic-binary");
        assertThat(events.getFirst().getThrowableProxy()).isNull();
    }

    @Test
    void ambientTransactionRetentionLogsEachCandidateId() {
        var decision = mock(LessonAudioCleanupDecisionService.class);
        List<ILoggingEvent> events;
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            events = captureLogs(LessonAudioCleanupService.class,
                    () -> new LessonAudioCleanupService(mock(com.learne.backend.teacher.content.audio.service.MediaCompensationService.class)).tryCleanup(1L, List.of(state("a"), state("b"))));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(events).hasSize(2);
        assertThat(events.getFirst().getFormattedMessage()).contains("publicId=" + state("a").publicId(), "course 1", "VIDEO");
        assertThat(events.getLast().getFormattedMessage()).contains("publicId=" + state("b").publicId(), "course 1", "VIDEO");
        verifyNoInteractions(decision, storage);
    }

    @Test
    void invalidCandidateIdsAreRedactedInRetentionLogs() {
        var input = new LessonAudioState("https://custom.example/private", "https://user:provider-credential@host/private\r\nforged");
        var events = captureLogs(LessonAudioCleanupDecisionService.class,
                () -> assertThat(decision().canDelete(1L, input)).isFalse());
        assertThat(events.getFirst().getFormattedMessage()).contains("publicId=<invalid>", "course 1", "VIDEO")
                .doesNotContain("provider-credential", "https://", "forged", "\r", "\n");
    }

    private void tick(LessonAudioCleanupDecisionService decision, List<LessonAudioState> states) {
        var jobs=mock(com.learne.backend.jobs.service.JobStore.class);
        when(jobs.isLeaseCurrent(any())).thenReturn(true);
        var mapper=new tools.jackson.databind.ObjectMapper();
        com.learne.backend.teacher.content.audio.service.MediaDeletePort fake=id->storage.delete(id,StorageResourceType.VIDEO);
        var handler=new com.learne.backend.teacher.content.audio.service.MediaCleanupHandler(jobs,decision,fake,mapper);
        for(var state:new java.util.LinkedHashSet<>(states)) {
            var payload=mapper.createObjectNode().put("courseId",1L).put("url",state.url()).put("publicId",state.publicId()).put("resourceType","VIDEO");
            var lease=new com.learne.backend.jobs.model.JobLease(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.time.Instant.now().plusSeconds(30),com.learne.backend.jobs.model.JobType.MEDIA_CLEANUP,1,1,3,mapper.writeValueAsString(payload),null,false);
            try { handler.execute(lease); } catch(RuntimeException expectedRetry) { /* Each job fails independently. */ }
        }
    }

    private List<ILoggingEvent> captureLogs(Class<?> type, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
