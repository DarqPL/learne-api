package com.learne.backend.admin.content.service;

import com.learne.backend.jobs.model.JobType;
import com.learne.backend.mail.model.MailPayload;
import com.learne.backend.mail.model.MailTemplate;
import com.learne.backend.mail.service.MailJobProducer;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Append-only historical decision and mail intent, owned by the review transaction. */
@Service
public class ReviewDecisionService {
    private final JdbcTemplate jdbc;
    private final MailJobProducer producer;

    public ReviewDecisionService(JdbcTemplate jdbc, MailJobProducer producer) {
        this.jdbc = jdbc;
        this.producer = producer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAndEnqueue(CourseRevision revision, UUID submissionId) {
        if (revision.getStatus() != CourseRevisionStatus.APPROVED && revision.getStatus() != CourseRevisionStatus.REJECTED)
            throw new IllegalArgumentException("Only accepted review decisions can be recorded");
        submissionId = jdbc.query("select id from course_submission_attempts where revision_id=? and course_id=? and state='SUCCEEDED' order by completed_at desc,id desc limit 1",
                (rs,row) -> rs.getObject(1,UUID.class),revision.getId(),revision.getCourse().getId()).stream().findFirst().orElse(null);
        UUID id = UUID.randomUUID();
        var teacher = revision.getTeacher();
        String email = teacher.getEmail().trim().toLowerCase(Locale.ROOT);
        String decision = revision.getStatus().name();
        String reason = revision.getStatus() == CourseRevisionStatus.REJECTED ? revision.getRejectionReason() : null;
        // PostgreSQL stores microseconds. Use exactly the same value in the row and payload.
        var reviewedAt = revision.getReviewedAt().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                insert into course_review_decisions(id,revision_id,course_id,submission_id,reviewer_id,
                    teacher_id,decision,course_name,recipient_email,rejection_reason,reviewed_at)
                values(?,?,?,?,?,?,?,?,?,?,?)
                """, id, revision.getId(), revision.getCourse().getId(), submissionId,
                revision.getReviewedBy().getId(), teacher.getId(), decision, revision.getName(), email,
                reason, Timestamp.from(reviewedAt));
        var payload = new MailPayload(teacher.getId(), email, teacher.getFullName(), MailTemplate.COURSE_REVIEW_RESULT,
                null, null, revision.getCourse().getId(), revision.getId(), submissionId, id, null,
                Map.of("courseName", revision.getName(), "decision", decision,
                        "rejectionReason", reason == null ? "" : reason, "reviewedAt", reviewedAt.toString()));
        producer.enqueue(JobType.COURSE_REVIEW_RESULT_MAIL, "course-review:" + id + ":" + teacher.getId(), payload, null);
    }
}
