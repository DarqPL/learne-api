package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learne.backend.storage.config.CloudinaryProperties;
import com.learne.backend.teacher.content.audio.dto.*;
import com.learne.backend.teacher.content.audio.service.*;
import com.learne.backend.teacher.content.repository.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.jobs.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MediaCleanupTests {
    @Test void staleLeaseNeverStartsDecisionOrDelete() {
        var store=mock(JobStore.class); var decision=mock(LessonAudioCleanupDecisionService.class);
        var delete=mock(MediaDeletePort.class);
        new MediaCleanupHandler(store,decision,delete,new ObjectMapper()).execute(lease());
        verifyNoInteractions(decision,delete);
    }
    @Test void eachAttemptRechecksDecisionAndLeaseBeforeExternalDelete() {
        var store=mock(JobStore.class); var decision=mock(LessonAudioCleanupDecisionService.class); var delete=mock(MediaDeletePort.class);
        when(store.isLeaseCurrent(any())).thenReturn(true,true,true,false);
        when(decision.evaluate(eq(1L),any())).thenReturn(new CleanupDecision(CleanupDecision.Kind.DELETE,"SAFE_ABSENCE"));
        var handler=new MediaCleanupHandler(store,decision,delete,new ObjectMapper());
        handler.execute(lease()); handler.execute(lease());
        verify(decision,times(2)).evaluate(eq(1L),any()); verify(delete).delete("learne/course-audio/a");
        verify(store).complete(any(),eq(JobState.SUCCEEDED),isNull());
    }
    @Test void databaseAndProviderFailuresRetryAndRetentionSkips() {
        var store=mock(JobStore.class); var decision=mock(LessonAudioCleanupDecisionService.class); var delete=mock(MediaDeletePort.class);
        when(store.isLeaseCurrent(any())).thenReturn(true);
        var handler=new MediaCleanupHandler(store,decision,delete,new ObjectMapper());
        when(decision.evaluate(eq(1L),any())).thenReturn(new CleanupDecision(CleanupDecision.Kind.RETRY,"DB_CHECK_FAILED"));
        assertThatThrownBy(()->handler.execute(lease())).isInstanceOf(RetryableJobException.class);
        when(decision.evaluate(eq(1L),any())).thenReturn(new CleanupDecision(CleanupDecision.Kind.RETAIN,"REFERENCED"));
        handler.execute(lease()); verify(store).complete(any(),eq(JobState.SKIPPED),eq("REFERENCED")); verifyNoInteractions(delete);
        when(decision.evaluate(eq(1L),any())).thenReturn(new CleanupDecision(CleanupDecision.Kind.DELETE,"SAFE_ABSENCE"));
        doThrow(new RetryableJobException("BUSY",null)).when(delete).delete(any());
        assertThatThrownBy(()->handler.execute(lease())).isInstanceOf(RetryableJobException.class);
    }
    private JobLease lease() {
        return new JobLease(UUID.randomUUID(),UUID.randomUUID(),java.time.Instant.now().plusSeconds(120),JobType.MEDIA_CLEANUP,1,1,3,
            "{\"courseId\":1,\"url\":\"https://res.cloudinary.com/test-cloud/video/upload/learne/course-audio/a.mp3\",\"publicId\":\"learne/course-audio/a\",\"resourceType\":\"VIDEO\"}",null,false);
    }
    @Test void databaseFailureIsRetryRatherThanRetention() {
        var courses=mock(CourseRepository.class);
        var properties=new CloudinaryProperties(); properties.setCloudName("test-cloud");
        when(courses.findLockedById(1L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private"));
        var service=new LessonAudioCleanupDecisionService(courses,mock(LessonRepository.class),mock(LessonRevisionRepository.class),properties);
        assertThat(service.evaluate(1L,LessonAudioCleanupServiceTests.state("a")).kind()).isEqualTo(CleanupDecision.Kind.RETRY);
    }
    @Test void eventIdentityDeduplicatesExactIdsAndNewMutationIsIndependent() {
        var queue=mock(JobEnqueuer.class); var mapper=new ObjectMapper();
        var producer=new MediaCleanupProducer(queue,mapper); var event=UUID.randomUUID();
        var state=LessonAudioCleanupServiceTests.state("a");
        producer.enqueue(event,1L,List.of(state,state));
        producer.enqueue(UUID.randomUUID(),1L,List.of(state));
        var capture=org.mockito.ArgumentCaptor.forClass(JobSpec.class); verify(queue,times(2)).enqueue(capture.capture());
        var jobs=capture.getAllValues();
        assertThat(jobs.getFirst().dedupeKey()).startsWith("media-cleanup:"+event+":").hasSize(115);
        assertThat(jobs.getLast().dedupeKey()).isNotEqualTo(jobs.getFirst().dedupeKey());
        assertThat(jobs.getLast().dedupeKey().substring(51)).isEqualTo(jobs.getFirst().dedupeKey().substring(51));
        assertThat(mapper.readTree(jobs.getFirst().payloadJson()).path("resourceType").asString()).isEqualTo("VIDEO");
    }
}
