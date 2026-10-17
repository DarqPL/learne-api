package com.learne.backend.teacher.content.service;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.Lesson;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RevisionAudioRebuildGuard {
    public void assertSafe(Course course, CourseRevision revision, Set<Long> deliberatelyDeletedLiveLessonIds) {
        Map<Long, Lesson> liveLessons = course.getTopics().stream()
                .flatMap(topic -> topic.getLessons().stream())
                .collect(Collectors.toMap(Lesson::getId, Function.identity()));
        for (var topic : revision.getTopics()) {
            for (var lesson : topic.getLessons()) {
                Long sourceId = lesson.getSourceLesson() == null ? null : lesson.getSourceLesson().getId();
                if (sourceId != null && deliberatelyDeletedLiveLessonIds.contains(sourceId)) {
                    continue;
                }
                if (lesson.getAudioUrl() == null && lesson.getAudioPublicId() == null) {
                    continue;
                }
                Lesson live = sourceId == null ? null : liveLessons.get(sourceId);
                if (live == null || !Objects.equals(lesson.getAudioUrl(), live.getAudioUrl())
                        || !Objects.equals(lesson.getAudioPublicId(), live.getAudioPublicId())) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "Audio in the revision differs from live content. Use the revision to edit this content.");
                }
            }
        }
    }
}
