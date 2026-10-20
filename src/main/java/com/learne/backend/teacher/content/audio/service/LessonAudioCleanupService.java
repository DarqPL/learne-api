package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.service.CourseWriteOutcome;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Best-effort compensation boundary. Provider deletion belongs exclusively to the MEDIA worker. */
@Service
public class LessonAudioCleanupService {
    private static final Logger log = LoggerFactory.getLogger(LessonAudioCleanupService.class);
    private final MediaCompensationService compensation;
    public LessonAudioCleanupService(MediaCompensationService compensation) { this.compensation = compensation; }

    public void compensate(UUID operationId, Long courseId, List<LessonAudioState> states,
            boolean persistenceBegan, CourseWriteOutcome outcome) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return;
        try {
            compensation.enqueueConfirmedRollback(operationId, courseId, states, persistenceBegan, outcome);
        } catch (RuntimeException failure) {
            if (states != null) for (var state : states) {
                log.warn("Media compensation enqueue failed publicId={} for course {} (target=VIDEO, {}); assets retained pending recovery",
                        LessonAudioCleanupDecisionService.candidatePublicId(state), courseId, failure.getClass().getSimpleName());
            }
        }
    }

    /** Legacy callers lack rollback evidence; retain rather than infer an outcome. */
    public void tryCleanup(Long courseId, List<LessonAudioState> states) {
        if (states != null) for (var state : states) {
            log.warn("Retaining lesson audio publicId={} for course {} (target=VIDEO): durable mutation intent or confirmed rollback required",
                    LessonAudioCleanupDecisionService.candidatePublicId(state), courseId);
        }
    }
}
