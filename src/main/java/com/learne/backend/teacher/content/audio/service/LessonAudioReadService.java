package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.submission.SubmissionGuard;
import com.learne.backend.teacher.content.audio.dto.AudioTargetKind;
import com.learne.backend.teacher.content.audio.dto.LessonAudioToken;
import com.learne.backend.teacher.content.audio.dto.CourseAudioTargetsResponse;
import com.learne.backend.teacher.content.audio.dto.CourseAudioTargetsResponse.Target;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import java.util.Comparator;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LessonAudioReadService {
    private final LessonAudioTargetResolver resolver;
    private final CourseRepository courses;
    private final CourseRevisionRepository revisions;
    private final SubmissionGuard submissionGuard;

    public LessonAudioReadService(LessonAudioTargetResolver resolver, CourseRepository courses,
                                  CourseRevisionRepository revisions, SubmissionGuard submissionGuard) {
        this.resolver = resolver;
        this.courses = courses;
        this.revisions = revisions;
        this.submissionGuard = submissionGuard;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public LessonAudioToken preflight(User teacher, AudioTargetKind kind, Long id) {
        var token = resolver.resolve(teacher, kind, id).token();
        submissionGuard.requireEditable(token.courseId());
        return token;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public CourseAudioTargetsResponse targets(User teacher, Long courseId) {
        var course = courses.findByIdAndTeacher(courseId, teacher).orElseThrow(LessonAudioReadService::notFound);
        var revision = revisions.findTopByCourseIdOrderByRevisionNumberDesc(courseId)
                .orElseThrow(LessonAudioReadService::conflict);
        if (!owned(teacher, course.getTeacher()) || !owned(teacher, revision.getTeacher())
                || revision.getCourse() == null || !Objects.equals(courseId, revision.getCourse().getId())) {
            throw notFound();
        }
        if (revision.getStatus() != CourseRevisionStatus.DRAFT
                && revision.getStatus() != CourseRevisionStatus.REJECTED) {
            throw conflict();
        }
        var targets = revision.getTopics().stream()
                .sorted(Comparator.comparingInt(TopicRevision::getOrderIndex).thenComparing(TopicRevision::getId))
                .flatMap(topic -> {
                    if (topic.getCourseRevision() == null
                            || !Objects.equals(revision.getId(), topic.getCourseRevision().getId())) throw notFound();
                    return topic.getLessons().stream()
                            .sorted(Comparator.comparingInt(LessonRevision::getOrderIndex).thenComparing(LessonRevision::getId))
                            .map(lesson -> {
                                if (!owned(teacher, lesson.getTeacher()) || lesson.getTopicRevision() == null
                                        || !Objects.equals(topic.getId(), lesson.getTopicRevision().getId())) throw notFound();
                                var source = lesson.getSourceLesson();
                                if (source != null && (!owned(teacher, source.getTeacher()) || source.getTopic() == null
                                        || source.getTopic().getCourse() == null
                                        || !Objects.equals(courseId, source.getTopic().getCourse().getId())
                                        || !owned(teacher, source.getTopic().getCourse().getTeacher()))) throw notFound();
                                return new Target(lesson.getId(), source == null ? null : source.getId(), topic.getId(),
                                        topic.getName(), lesson.getTitle(), lesson.getSkillType(), lesson.getOrderIndex(),
                                        lesson.getAudioUrl(), lesson.getAudioPublicId());
                            });
                }).toList();
        return new CourseAudioTargetsResponse(courseId, revision.getId(), revision.getStatus(), targets);
    }

    private static boolean owned(User teacher, User owner) {
        return teacher != null && teacher.getId() != null && owner != null
                && Objects.equals(teacher.getId(), owner.getId());
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "Course audio targets not found");
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "Latest course revision is not editable or does not exist");
    }
}
