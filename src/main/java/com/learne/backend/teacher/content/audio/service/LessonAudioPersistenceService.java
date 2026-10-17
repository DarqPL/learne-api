package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.audio.dto.AudioTargetKind;
import com.learne.backend.teacher.content.audio.dto.LessonAudioMutation;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.audio.dto.LessonAudioToken;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.LessonRevisionRepository;
import com.learne.backend.teacher.content.service.CourseWriteLockService;
import com.learne.backend.teacher.content.service.CourseWriteOutcome;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short database-only transactions; callers perform external work after proxy completion. */
@Service
public class LessonAudioPersistenceService {
    private final CourseWriteLockService locks;
    private final LessonAudioTargetResolver resolver;
    private final LessonRepository lessons;
    private final LessonRevisionRepository revisionLessons;
    private final EntityManager entityManager;

    public LessonAudioPersistenceService(CourseWriteLockService locks, LessonAudioTargetResolver resolver,
                                         LessonRepository lessons, LessonRevisionRepository revisionLessons,
                                         EntityManager entityManager) {
        this.locks = locks;
        this.resolver = resolver;
        this.lessons = lessons;
        this.revisionLessons = revisionLessons;
        this.entityManager = entityManager;
    }

    @Transactional
    public LessonAudioMutation replace(User teacher, LessonAudioToken before, LessonAudioState newState,
                                       CourseWriteOutcome outcome) {
        outcome.observe();
        LessonAudioTargetResolver.ResolvedTarget target;
        try {
            locks.teacherCourse(before.courseId(), teacher);
            target = resolver.resolve(teacher, before.kind(), before.targetId());
        } catch (ApiException exception) {
            // A valid preflight already existed: disappearance/ownership changes are stale writes.
            throw conflict();
        }
        if (!before.equals(target.token())) throw conflict();
        return mutate(target, newState);
    }

    @Transactional
    public LessonAudioMutation remove(User teacher, AudioTargetKind kind, Long id) {
        // Scalar discovery loads no managed graph before the entry-only course lock clears the context.
        Long courseId = (kind == AudioTargetKind.LIVE
                ? lessons.findCourseIdByLessonIdAndTeacherId(id, teacher.getId())
                : revisionLessons.findCourseIdByLessonIdAndTeacherId(id, teacher.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Lesson audio target not found"));
        locks.teacherCourse(courseId, teacher);
        var target = resolver.resolve(teacher, kind, id);
        if (!courseId.equals(target.course().getId())) throw conflict();
        return mutate(target, LessonAudioState.empty());
    }

    private LessonAudioMutation mutate(LessonAudioTargetResolver.ResolvedTarget target, LessonAudioState state) {
        var oldStates = target.oldStates(state);
        target.apply(state);
        entityManager.flush();
        return new LessonAudioMutation(target.response(), target.course().getId(), oldStates);
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "Lesson audio target changed; retry with a fresh snapshot");
    }
}
