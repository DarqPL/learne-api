package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.teacher.content.audio.dto.CleanupDecision;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

public class CourseCoverCleanupDecisionService {
    private static final Pattern MANAGED_ID = Pattern.compile("learne/course-covers/[A-Za-z0-9_-]+");
    private final CourseRepository courses;
    private final JdbcTemplate jdbc;

    public CourseCoverCleanupDecisionService(CourseRepository courses) {
        this(courses, null);
    }

    public CourseCoverCleanupDecisionService(CourseRepository courses, JdbcTemplate jdbc) {
        this.courses = courses;
        this.jdbc = jdbc;
    }

    @Transactional
    public CleanupDecision evaluate(long courseId, String url, String publicId, UUID jobId) {
        if (publicId == null || !MANAGED_ID.matcher(publicId).matches()) {
            return new CleanupDecision(CleanupDecision.Kind.RETAIN, "UNPROVEN_PROVENANCE");
        }
        if (jobId == null) {
            throw new IllegalArgumentException("Cleanup job identity is required");
        }
        var course = courses.findLockedById(courseId);
        if (course.isPresent()) {
            Course current = course.get();
            if (publicId.equals(current.getImagePublicId())
                    || (url != null && url.equals(current.getImageUrl()))) {
                return retained(publicId, jobId);
            }
        }
        if (hasLiveReference(publicId, url)) {
            return retained(publicId, jobId);
        }
        return claim(publicId, courseId, url, jobId)
                ? new CleanupDecision(CleanupDecision.Kind.DELETE,
                        course.isEmpty() ? "MISSING_COURSE" : "SAFE_ABSENCE")
                : new CleanupDecision(CleanupDecision.Kind.RETRY, "ALREADY_CLAIMED");
    }

    public boolean isPublicIdClaimed(String publicId) {
        if (jdbc == null || publicId == null) return false;
        Integer count = jdbc.queryForObject(
                "select count(*) from course_cover_cleanup_claims where public_id = ? and status in ('CLAIMED','DEAD')",
                Integer.class, publicId);
        return count != null && count > 0;
    }

    public void completeClaim(String publicId, UUID jobId) {
        if (jdbc != null) {
            jdbc.update("delete from course_cover_cleanup_claims where public_id = ? and job_id = ?",
                    publicId, jobId);
        }
    }

    public void markClaimDead(String publicId, UUID jobId, String errorCode) {
        if (jdbc != null) {
            jdbc.update("""
                    update course_cover_cleanup_claims
                    set status='DEAD', last_error_code=?, updated_at=CURRENT_TIMESTAMP
                    where public_id=? and job_id=? and status='CLAIMED'
                    """, errorCode, publicId, jobId);
        }
    }

    private CleanupDecision retained(String publicId, UUID jobId) {
        return markReferenced(publicId, jobId)
                ? new CleanupDecision(CleanupDecision.Kind.RETAIN, "REFERENCED")
                : new CleanupDecision(CleanupDecision.Kind.RETRY, "ALREADY_CLAIMED");
    }

    private boolean hasLiveReference(String publicId, String url) {
        if (jdbc == null) return false;
        Boolean referenced = jdbc.queryForObject("""
                select exists (
                    select 1 from courses
                    where image_public_id = ? or image_url = ?
                    union all
                    select 1
                    from course_cover_reviews review
                    join course_pending_review_snapshots snapshot
                      on snapshot.course_id = review.course_id
                     and snapshot.cover_review_id = review.id
                    where snapshot.snapshot_kind = 'COVER_ONLY'
                      and (review.image_public_id = ? or review.image_url = ?)
                    union all
                    select 1
                    from course_revisions revision
                    where (
                        revision.status in ('DRAFT', 'REJECTED')
                        or exists (
                            select 1 from course_pending_review_snapshots snapshot
                            where snapshot.course_revision_id = revision.id
                              and snapshot.course_id = revision.course_id
                        )
                    )
                      and (revision.cover_image_public_id = ? or revision.cover_image_url = ?)
                )
                """, Boolean.class, publicId, url, publicId, url, publicId, url);
        return Boolean.TRUE.equals(referenced);
    }

    private boolean markReferenced(String publicId, UUID jobId) {
        if (jdbc == null) return true;
        int changed = jdbc.update("""
                update course_cover_cleanup_claims
                set status='RETAINED', job_id=NULL, last_error_code=NULL, updated_at=CURRENT_TIMESTAMP
                where public_id=? and status='CLAIMED' and job_id=?
                """, publicId, jobId);
        if (changed > 0) return true;

        Integer activeClaims = jdbc.queryForObject("""
                select count(*) from course_cover_cleanup_claims
                where public_id=? and status='CLAIMED'
                """, Integer.class, publicId);
        if (activeClaims != null && activeClaims > 0) return false;

        jdbc.update("""
                update course_cover_cleanup_claims
                set status='RETAINED', job_id=NULL, last_error_code=NULL, updated_at=CURRENT_TIMESTAMP
                where public_id=? and status='DEAD'
                """, publicId);
        return true;
    }

    private boolean claim(String publicId, long courseId, String url, UUID jobId) {
        if (jdbc == null) return true;
        int reclaimed = jdbc.update("""
                update course_cover_cleanup_claims
                set course_id=?, url=?, status='CLAIMED', job_id=?, last_error_code=NULL,
                    updated_at=CURRENT_TIMESTAMP
                where public_id=? and (status='RETAINED' or job_id=?)
                """, courseId, url, jobId, publicId, jobId);
        if (reclaimed > 0) return true;

        return jdbc.update("""
                insert into course_cover_cleanup_claims(
                    public_id, course_id, url, status, job_id, last_error_code)
                values (?, ?, ?, 'CLAIMED', ?, NULL)
                on conflict (public_id) do nothing
                """, publicId, courseId, url, jobId) == 1;
    }
}
