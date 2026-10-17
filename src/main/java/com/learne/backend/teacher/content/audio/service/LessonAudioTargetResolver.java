package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.audio.dto.AudioTargetKind;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.audio.dto.LessonAudioToken;
import com.learne.backend.teacher.content.dto.LessonResponse;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.LessonRevisionRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Resolve within a read snapshot, or after acquiring the course write lock. */
@Component
public class LessonAudioTargetResolver {
    private final LessonRepository lessons;
    private final LessonRevisionRepository revisionLessons;
    private final CourseRevisionRepository revisions;

    public LessonAudioTargetResolver(LessonRepository lessons, LessonRevisionRepository revisionLessons,
                                     CourseRevisionRepository revisions) {
        this.lessons = lessons;
        this.revisionLessons = revisionLessons;
        this.revisions = revisions;
    }

    public ResolvedTarget resolve(User teacher, AudioTargetKind kind, Long id) {
        return switch (kind) {
            case LIVE -> resolveLive(teacher, id);
            case REVISION -> resolveRevision(teacher, id);
        };
    }

    private ResolvedTarget resolveLive(User teacher, Long id) {
        Lesson live = lessons.findById(id).orElseThrow(LessonAudioTargetResolver::notFound);
        Course course = live.getTopic() == null ? null : live.getTopic().getCourse();
        if (!owned(teacher, live.getTeacher()) || course == null || !owned(teacher, course.getTeacher())) {
            throw notFound();
        }
        CourseRevision latest = latest(course);
        if (!sameId(course.getId(), latest.getCourse() == null ? null : latest.getCourse().getId())
                || !owned(teacher, latest.getTeacher())) {
            throw conflict();
        }
        requireEditable(latest);
        if (!unpublished(course) || !editable(course.getStatus()) || !editable(live.getStatus())
                || live.getPublishedAt() != null) {
            throw conflict();
        }
        var mirrors = latest.getTopics().stream()
                .flatMap(topic -> topic.getLessons().stream())
                .filter(lesson -> lesson.getSourceLesson() != null
                        && sameId(live.getId(), lesson.getSourceLesson().getId()))
                .toList();
        if (mirrors.size() != 1) throw conflict();
        LessonRevision mirror = mirrors.getFirst();
        boolean validGraph = latest.getTopics().stream().anyMatch(topic ->
                mirror.getTopicRevision() != null
                        && sameId(topic.getId(), mirror.getTopicRevision().getId())
                        && topic.getCourseRevision() != null
                        && sameId(latest.getId(), topic.getCourseRevision().getId())
                        && mirror.getTopicRevision().getCourseRevision() != null
                        && sameId(latest.getId(), mirror.getTopicRevision().getCourseRevision().getId())
                        && topic.getLessons().contains(mirror));
        Lesson source = mirror.getSourceLesson();
        if (!validGraph || !owned(teacher, mirror.getTeacher())
                || !validSource(teacher, course, source)
                || !LessonAudioState.from(live).equals(LessonAudioState.from(mirror))) {
            throw conflict();
        }
        return new ResolvedTarget(AudioTargetKind.LIVE, course, latest, live, mirror);
    }

    private ResolvedTarget resolveRevision(User teacher, Long id) {
        LessonRevision lesson = revisionLessons.findById(id).orElseThrow(LessonAudioTargetResolver::notFound);
        CourseRevision revision = lesson.getTopicRevision() == null ? null
                : lesson.getTopicRevision().getCourseRevision();
        Course course = revision == null ? null : revision.getCourse();
        if (!owned(teacher, lesson.getTeacher()) || revision == null || !owned(teacher, revision.getTeacher())
                || course == null || !owned(teacher, course.getTeacher())) {
            throw notFound();
        }
        Lesson source = lesson.getSourceLesson();
        if (source != null && !validSource(teacher, course, source)) throw notFound();
        CourseRevision latest = latest(course);
        if (!sameId(revision.getId(), latest.getId())) throw conflict();
        requireEditable(latest);
        return new ResolvedTarget(AudioTargetKind.REVISION, course, revision,
                unpublished(course) ? source : null, lesson);
    }

    private CourseRevision latest(Course course) {
        return revisions.findTopByCourseIdOrderByRevisionNumberDesc(course.getId())
                .orElseThrow(LessonAudioTargetResolver::conflict);
    }

    private static void requireEditable(CourseRevision revision) {
        if (revision.getStatus() != CourseRevisionStatus.DRAFT
                && revision.getStatus() != CourseRevisionStatus.REJECTED) throw conflict();
    }

    private static boolean editable(ContentStatus status) {
        return status == ContentStatus.DRAFT || status == ContentStatus.REJECTED;
    }

    private static boolean unpublished(Course course) {
        return course.getPublishedAt() == null && course.getStatus() != ContentStatus.APPROVED;
    }

    private static boolean validSource(User teacher, Course course, Lesson source) {
        return owned(teacher, source.getTeacher()) && source.getTopic() != null
                && source.getTopic().getCourse() != null
                && sameId(course.getId(), source.getTopic().getCourse().getId())
                && owned(teacher, source.getTopic().getCourse().getTeacher());
    }

    private static boolean owned(User teacher, User owner) {
        return teacher != null && owner != null && sameId(teacher.getId(), owner.getId());
    }

    private static boolean sameId(Long left, Long right) {
        return left != null && Objects.equals(left, right);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "Lesson audio target not found");
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "Lesson audio target or revision is not editable or consistent");
    }

    public record ResolvedTarget(AudioTargetKind kind, Course course, CourseRevision revision,
                                  Lesson liveLesson, LessonRevision revisionLesson) {
        /** Only the resolved write set is eligible for later cleanup, never historical rows. */
        public List<LessonAudioState> oldStates(LessonAudioState replacement) {
            var states = new LinkedHashSet<LessonAudioState>();
            states.add(LessonAudioState.from(revisionLesson));
            if (liveLesson != null) states.add(LessonAudioState.from(liveLesson));
            return states.stream().filter(state -> !state.equals(replacement)
                    && (state.url() != null || state.publicId() != null)).toList();
        }

        public void apply(LessonAudioState state) {
            revisionLesson.setAudioUrl(state.url());
            revisionLesson.setAudioPublicId(state.publicId());
            if (liveLesson != null) {
                liveLesson.setAudioUrl(state.url());
                liveLesson.setAudioPublicId(state.publicId());
            }
        }

        public LessonResponse response() {
            return kind == AudioTargetKind.LIVE ? LessonResponse.from(liveLesson)
                    : LessonResponse.from(revisionLesson, ContentStatus.valueOf(revision.getStatus().name()));
        }

        public LessonAudioToken token() {
            if (kind == AudioTargetKind.LIVE) {
                return new LessonAudioToken(kind, liveLesson.getId(), course.getId(), revision.getId(),
                        LessonAudioState.from(liveLesson), revisionLesson.getId(), LessonAudioState.from(revisionLesson));
            }
            return new LessonAudioToken(kind, revisionLesson.getId(), course.getId(), revision.getId(),
                    LessonAudioState.from(revisionLesson), liveLesson == null ? null : liveLesson.getId(),
                    liveLesson == null ? null : LessonAudioState.from(liveLesson));
        }
    }
}
