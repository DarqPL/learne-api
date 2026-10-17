package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.storage.service.StorageService;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LessonAudioCleanupService {
    private static final Logger log = LoggerFactory.getLogger(LessonAudioCleanupService.class);
    private final LessonAudioCleanupDecisionService decision;
    private final StorageService storage;

    public LessonAudioCleanupService(LessonAudioCleanupDecisionService decision, StorageService storage) {
        this.decision = decision;
        this.storage = storage;
    }

    public void tryCleanup(Long courseId, List<LessonAudioState> states) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (states != null) {
                for (LessonAudioState state : states) {
                    log.warn("Retaining lesson audio publicId={} for course {} (target=VIDEO): cleanup called inside a transaction",
                            LessonAudioCleanupDecisionService.candidatePublicId(state), courseId);
                }
            }
            return;
        }
        if (states == null) {
            return;
        }
        Set<String> attempted = new HashSet<>();
        for (LessonAudioState state : states) {
            if (state == null || state.publicId() == null) {
                log.warn("Retaining lesson audio publicId=<missing> for course {} (target=VIDEO): missing managed public ID", courseId);
                continue;
            }
            if (!attempted.add(state.publicId())) {
                continue;
            }
            try {
                // Separate bean/proxy: REQUIRES_NEW completes and releases the lock before delete.
                if (decision.canDelete(courseId, state)) {
                    storage.delete(state.publicId(), StorageResourceType.VIDEO);
                }
            } catch (RuntimeException exception) {
                // Do not log provider messages, URLs, credentials or uploaded bytes.
                log.warn("Lesson audio cleanup failed publicId={} for course {} (target=VIDEO, {}); continuing",
                        LessonAudioCleanupDecisionService.candidatePublicId(state), courseId,
                        exception.getClass().getSimpleName());
            }
        }
    }
}
