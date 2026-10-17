package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.content.audio.dto.AudioTargetKind;
import com.learne.backend.teacher.content.audio.dto.LessonAudioMutation;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.dto.LessonResponse;
import com.learne.backend.teacher.content.service.CourseWriteOutcome;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional(propagation = Propagation.NEVER)
public class LessonAudioService {
    private static final Logger log = LoggerFactory.getLogger(LessonAudioService.class);
    private final LessonAudioReadService reads;
    private final LessonAudioFileService files;
    private final StorageService storage;
    private final LessonAudioPersistenceService persistence;
    private final LessonAudioCleanupService cleanup;
    private final LessonAudioPostCommitService postCommit;

    public LessonAudioService(LessonAudioReadService reads, LessonAudioFileService files, StorageService storage,
                               LessonAudioPersistenceService persistence, LessonAudioCleanupService cleanup,
                               LessonAudioPostCommitService postCommit) {
        this.reads = reads;
        this.files = files;
        this.storage = storage;
        this.persistence = persistence;
        this.cleanup = cleanup;
        this.postCommit = postCommit;
    }

    public LessonResponse upload(User teacher, AudioTargetKind kind, Long id, MultipartFile input) {
        var before = reads.preflight(teacher, kind, id);
        var file = files.validateAndScan(input);
        var uploaded = storage.uploadLessonAudio(file);
        if (uploaded == null || uploaded.url() == null || uploaded.url().isBlank()
                || uploaded.publicId() == null || uploaded.publicId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Audio storage returned incomplete metadata");
        }
        var newState = new LessonAudioState(uploaded.url(), uploaded.publicId());
        var outcome = new CourseWriteOutcome();
        LessonAudioMutation mutation;
        try {
            mutation = persistence.replace(teacher, before, newState, outcome);
        } catch (RuntimeException original) {
            if (outcome.rolledBack()) {
                try {
                    cleanup.tryCleanup(before.courseId(), List.of(newState));
                } catch (RuntimeException cleanupFailure) {
                    log.warn("Audio rollback cleanup failed for course {} ({})", before.courseId(),
                            cleanupFailure.getClass().getSimpleName());
                }
            } else {
                log.warn("Audio write completion is not a known rollback for course {}; retaining uploaded asset",
                        before.courseId());
            }
            throw original;
        }
        postCommit.complete(teacher.getId(), mutation.courseId(), mutation.cleanupCandidates());
        return mutation.response();
    }

    public LessonResponse delete(User teacher, AudioTargetKind kind, Long id) {
        var mutation = persistence.remove(teacher, kind, id);
        postCommit.complete(teacher.getId(), mutation.courseId(), mutation.cleanupCandidates());
        return mutation.response();
    }
}
