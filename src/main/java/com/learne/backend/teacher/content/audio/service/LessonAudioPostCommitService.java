package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LessonAudioPostCommitService {
    private static final Logger log = LoggerFactory.getLogger(LessonAudioPostCommitService.class);
    private final CacheInvalidationService cache;
    private final LessonAudioCleanupService cleanup;

    public LessonAudioPostCommitService(CacheInvalidationService cache, LessonAudioCleanupService cleanup) {
        this.cache = cache;
        this.cleanup = cleanup;
    }

    public void complete(Long teacherId, Long courseId, List<LessonAudioState> oldStates) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("Audio post-commit work for course {} requires a completed transaction; retaining audio", courseId);
            return;
        }
        try {
            cache.evictContentAfterAudioCommit(teacherId);
        } catch (RuntimeException exception) {
            log.warn("Audio post-commit cache invalidation failed for course {} ({})", courseId,
                    exception.getClass().getSimpleName());
        }
    }
}
