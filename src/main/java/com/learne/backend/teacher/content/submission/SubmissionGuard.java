package com.learne.backend.teacher.content.submission;

import com.learne.backend.shared.api.CodedApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Teacher writers call immediately after the course lock, before loading child graphs.
 * Read-only preflights may also call this, but never replace the persistence check. */
@Service
public class SubmissionGuard {
    private final JdbcTemplate jdbc;

    public SubmissionGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void requireEditable(long courseId) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from course_submission_attempts
                where course_id=? and state in ('QUEUED','PROCESSING'))
                """, Boolean.class, courseId))) {
            throw new CodedApiException(HttpStatus.CONFLICT,
                    "Khóa học đang được xử lý để gửi duyệt. Vui lòng thử lại sau.",
                    "COURSE_SUBMISSION_IN_PROGRESS");
        }
    }
}
