package com.learne.backend.notification;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationRepository {
    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Notification intent must commit atomically with the caller's state transition. */
    @Transactional(propagation = Propagation.MANDATORY, timeout = 5)
    public boolean insertIfAbsent(Intent intent) {
        return jdbc.update("""
                insert into user_notifications(id,user_id,type,submission_id,course_id,revision_id,dedupe_key,message)
                values (?,?,?,?,?,?,?,?) on conflict (dedupe_key) do nothing
                """, intent.id(),intent.userId(),intent.type(),intent.submissionId(),intent.courseId(),intent.revisionId(),
                intent.dedupeKey(),intent.message()) == 1;
    }

    @Transactional(readOnly = true, timeout = 5)
    public long countUnread(long userId) {
        return jdbc.queryForObject("select count(*) from user_notifications where user_id=? and read_at is null",Long.class,userId);
    }

    @Transactional(timeout = 5)
    public boolean markRead(UUID id, long userId) {
        return jdbc.update("update user_notifications set read_at=current_timestamp where id=? and user_id=? and read_at is null",id,userId) == 1;
    }

    public record Intent(UUID id, Long userId, String type, UUID submissionId, Long courseId, Long revisionId,
            String dedupeKey, String message) { }
}
