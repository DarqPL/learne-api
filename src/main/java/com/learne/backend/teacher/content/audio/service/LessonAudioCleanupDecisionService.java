package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.storage.config.CloudinaryProperties;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.audio.dto.CleanupDecision;
import static com.learne.backend.teacher.content.audio.dto.CleanupDecision.Kind.*;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.LessonRevisionRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LessonAudioCleanupDecisionService {
    private static final Logger log = LoggerFactory.getLogger(LessonAudioCleanupDecisionService.class);
    private static final Pattern MANAGED_ID = Pattern.compile("learne/course-audio/[A-Za-z0-9_-]+");
    private final CourseRepository courses;
    private final LessonRepository live;
    private final LessonRevisionRepository revisions;
    private final CloudinaryProperties properties;

    public LessonAudioCleanupDecisionService(CourseRepository courses, LessonRepository live,
            LessonRevisionRepository revisions, CloudinaryProperties properties) {
        this.courses = courses;
        this.live = live;
        this.revisions = revisions;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean canDelete(Long courseId, LessonAudioState state) {
        return evaluate(courseId, state).kind() == DELETE;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public CleanupDecision evaluate(Long courseId, LessonAudioState state) {
        if (!hasManagedProvenance(state)) {
            return retain(courseId, state, "UNPROVEN_PROVENANCE");
        }
        if (courseId == null) {
            return retain(null, state, "MISSING_COURSE");
        }
        try {
            // Course first, before any reference reads. No provider I/O in this transaction.
            if (courses.findLockedById(courseId).isEmpty()) {
                return retain(courseId, state, "MISSING_COURSE");
            }
            if (live.existsByAudioPublicId(state.publicId()) || live.existsByAudioUrl(state.url())
                    || revisions.existsByAudioPublicId(state.publicId()) || revisions.existsByAudioUrl(state.url())
                    || live.existsByAudioUrlContaining(state.publicId())
                    || revisions.existsByAudioUrlContaining(state.publicId())) {
                return retain(courseId, state, "REFERENCED");
            }
            if (hasUncertainReference(state.publicId(), live.findAllAudioUrls())
                    || hasUncertainReference(state.publicId(), revisions.findAllAudioUrls())) {
                return retain(courseId, state, "AMBIGUOUS_REFERENCE");
            }
            /* Reintroduction proof (not just a reference count): TeacherContentService creates
             * revisions from this course's locked live/latest graph; AdminContentReviewService
             * materializes this course's locked revision. Import's writer copies blank audio
             * from its locked current source, or uses a fresh server upload. Body DTOs expose
             * no audio ID/URL injection, and no writer copies audio from another course.
             * Thus after global absence under this lock, no supported writer has a source
             * from which to reintroduce this ID after the decision transaction releases it.
             * A future cross-course copy/snapshot writer must extend this protocol or retain.
             */
            return new CleanupDecision(DELETE, "SAFE_ABSENCE");
        } catch (RuntimeException exception) {
            log.warn("Retrying lesson audio publicId={} for course {} (target=VIDEO): DB_CHECK_FAILED",
                    candidatePublicId(state), courseId);
            return new CleanupDecision(RETRY, "DB_CHECK_FAILED");
        }
    }

    public static boolean hasUncertainReference(String publicId, List<String> urls) {
        if (urls == null) {
            return true;
        }
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            try {
                // Inspect ALL references, not a SQL-selected subset of spelling variants.
                // Decoding/normalization can only veto deletion, never grant candidate provenance.
                String path = URI.create(url).getPath();
                if (path == null || path.isBlank() || path.contains(publicId) || path.contains("%")
                        || path.contains("..") || path.contains("\\") || path.contains(";")) {
                    return true;
                }
                URI pathUri = new URI(null, null, path, null);
                if (!pathUri.normalize().equals(pathUri)) {
                    // Dot segments / repeated separators have uncertain delivery semantics: retain.
                    return true;
                }
            } catch (IllegalArgumentException | URISyntaxException exception) {
                return true;
            }
        }
        return false;
    }

    private boolean hasManagedProvenance(LessonAudioState state) {
        return hasManagedProvenance(state, properties.getCloudName());
    }

    public static boolean hasManagedProvenance(LessonAudioState state, String cloud) {
        if (state == null || state.publicId() == null || state.url() == null
                || !MANAGED_ID.matcher(state.publicId()).matches()
                || cloud == null || !cloud.matches("[A-Za-z0-9_-]+")) {
            return false;
        }
        try {
            URI uri = URI.create(state.url());
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return false;
            }
            String prefix;
            if ("res.cloudinary.com".equals(uri.getHost())) {
                prefix = "/" + cloud + "/video/upload/";
            } else if ((cloud + ".res.cloudinary.com").equals(uri.getHost())) {
                prefix = "/video/upload/";
            } else {
                return false;
            }
            return uri.getRawPath() != null && uri.getRawPath().matches(Pattern.quote(prefix)
                    + "(?:v[0-9]+/)?" + Pattern.quote(state.publicId()) + "(?:\\.(?:mp3|wav|m4a|ogg|mp4|aac|flac|webm))?");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static String candidatePublicId(LessonAudioState state) {
        if (state == null || state.publicId() == null || state.publicId().isBlank()) {
            return "<missing>";
        }
        // Diagnostic IDs must not become URL/credential or multiline log injection channels.
        return state.publicId().matches("[A-Za-z0-9_./-]{1,255}") ? state.publicId() : "<invalid>";
    }

    private CleanupDecision retain(Long courseId, LessonAudioState state, String reason) {
        log.warn("Retaining lesson audio publicId={} for course {} (target=VIDEO): {}",
                candidatePublicId(state), courseId, reason);
        return new CleanupDecision(RETAIN, reason);
    }
}
