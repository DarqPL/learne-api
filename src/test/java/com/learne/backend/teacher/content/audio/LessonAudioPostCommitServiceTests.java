package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

import com.learne.backend.cache.*;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.audio.service.LessonAudioCleanupService;
import com.learne.backend.teacher.content.audio.service.LessonAudioPostCommitService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LessonAudioPostCommitServiceTests {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void eachCacheActionFailureStillAttemptsEveryOtherActionAndCleanup(int failure) {
        CacheManager manager = mock(CacheManager.class);
        Cache teacher = mock(Cache.class), catalog = mock(Cache.class), detail = mock(Cache.class), admin = mock(Cache.class);
        when(manager.getCache(CacheNames.TEACHER_DASHBOARD)).thenReturn(teacher);
        when(manager.getCache(CacheNames.STUDENT_CATALOG)).thenReturn(catalog);
        when(manager.getCache(CacheNames.STUDENT_COURSE_DETAIL)).thenReturn(detail);
        when(manager.getCache(CacheNames.ADMIN_DASHBOARD)).thenReturn(admin);
        CatalogCacheVersionService versions = mock(CatalogCacheVersionService.class);
        RuntimeException unavailable = new IllegalStateException("cache unavailable");
        switch (failure) {
            case 0 -> doThrow(unavailable).when(teacher).evictIfPresent(any());
            case 1 -> doThrow(unavailable).when(catalog).invalidate();
            case 2 -> doThrow(unavailable).when(detail).invalidate();
            case 3 -> doThrow(unavailable).when(versions).bumpVersion();
            case 4 -> doThrow(unavailable).when(admin).evictIfPresent(any());
        }
        var cache = new CacheInvalidationService(manager, versions, mock(ObservedCacheMetrics.class));
        var cleanup = mock(LessonAudioCleanupService.class);
        List<LessonAudioState> states = List.of(LessonAudioCleanupServiceTests.state("old"));
        new LessonAudioPostCommitService(cache, cleanup).complete(2L, 1L, states);
        verify(teacher).evictIfPresent(CacheKeySupport.teacherDashboard(2L));
        verify(catalog).invalidate();
        verify(detail).invalidate();
        verify(versions).bumpVersion();
        verify(admin).evictIfPresent(CacheKeySupport.adminDashboard());
        verifyNoInteractions(cleanup);
    }

    @Test
    void aggregateCacheFailureAndCleanupFailureNeverEscape() {
        var cache = mock(CacheInvalidationService.class);
        var cleanup = mock(LessonAudioCleanupService.class);
        doThrow(new IllegalStateException("cache")).when(cache).evictContentAfterAudioCommit(2L);
        doThrow(new IllegalStateException("cleanup")).when(cleanup).tryCleanup(1L, List.of());
        assertThatCode(() -> new LessonAudioPostCommitService(cache, cleanup).complete(2L, 1L, List.of()))
                .doesNotThrowAnyException();
        verifyNoInteractions(cleanup);
    }

    @Test
    void postCommitWorkRefusesAnActualAmbientTransaction() {
        var cache = mock(CacheInvalidationService.class);
        var cleanup = mock(LessonAudioCleanupService.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            new LessonAudioPostCommitService(cache, cleanup).complete(2L, 1L, List.of());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(cache, cleanup);
    }
}
