package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.service.CourseWriteOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Called only after the original proxy has completed, with explicit rollback evidence. */
@Service
public class MediaCompensationService {
    private final MediaCleanupProducer producer;
    public MediaCompensationService(MediaCleanupProducer producer) { this.producer = producer; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueConfirmedRollback(UUID operationId, Long courseId, List<LessonAudioState> assets,
            boolean persistenceBegan, CourseWriteOutcome outcome) {
        if (persistenceBegan && (outcome == null || !outcome.rolledBack())) return;
        // Without a surviving course there is no lock/provenance proof. Retain conservatively.
        if (courseId == null || courseId <= 0 || assets == null || assets.isEmpty()) return;
        producer.enqueue(operationId, courseId, assets);
    }
}
