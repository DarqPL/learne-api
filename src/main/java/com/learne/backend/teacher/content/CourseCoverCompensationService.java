package com.learne.backend.teacher.content;

import com.learne.backend.teacher.content.audio.service.MediaCleanupProducer;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseCoverCompensationService {
    private final MediaCleanupProducer cleanup;

    public CourseCoverCompensationService(MediaCleanupProducer cleanup) {
        this.cleanup = cleanup;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueFailedPersistence(long courseId, String url, String publicId) {
        cleanup.enqueueCourseCover(UUID.randomUUID(), courseId, url, publicId);
    }
}
