package com.learne.backend.teacher.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.admin.content.service.AdminCourseCoverReviewService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
class CourseCoverModerationPersistenceTests {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminCourseCoverReviewService adminCoverReviews;

    @Test
    void migrationPreservesAnExistingApprovedCourseCover() throws SQLException {
        String databaseName = "cover_moderation_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration-h2")
                    .target("30")
                    .load()
                    .migrate();
            JdbcTemplate migrationJdbc = new JdbcTemplate(dataSource);
            migrationJdbc.update("""
                    insert into users (id, email, password_hash, full_name, role)
                    values (1, 'legacy-cover@example.com', 'hash', 'Legacy Teacher', 'TEACHER')
                    """);
            migrationJdbc.update("""
                    insert into courses
                        (id, teacher_id, name, type, level, order_index, status, image_url, image_public_id)
                    values
                        (10, 1, 'Legacy Covered Course', 'GRAMMAR', 'A1', 0, 'APPROVED',
                         'https://example.test/approved.jpg', 'approved-cover')
                    """);
            migrationJdbc.update("""
                    insert into course_revisions
                        (id, course_id, teacher_id, revision_number, status, name, type, level, order_index,
                         created_at, updated_at)
                    values
                        (20, 10, 1, 2, 'PENDING_REVIEW', 'Pending Course', 'GRAMMAR', 'A1', 0,
                         current_timestamp, current_timestamp)
                    """);

            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration-h2")
                    .load()
                    .migrate();

            assertThat(migrationJdbc.queryForMap(
                    "select image_url, image_public_id from courses where id = 10"))
                    .containsEntry("image_url", "https://example.test/approved.jpg")
                    .containsEntry("image_public_id", "approved-cover");
            assertThat(migrationJdbc.queryForMap(
                    "select snapshot_kind, course_revision_id from course_pending_review_snapshots where course_id = 10"))
                    .containsEntry("snapshot_kind", "COURSE_REVISION")
                    .containsEntry("course_revision_id", 20L);
            dataSource.destroy();
        }
    }

    @Test
    void revisionCoverCandidateMustBePresentAsAUrlAndPublicIdPair() {
        long courseId = createCourse();
        long teacherId = teacherIdFor(courseId);
        jdbc.update("""
                insert into course_revisions
                    (course_id, teacher_id, revision_number, status, name, type, level, order_index,
                     created_at, updated_at, cover_image_url, cover_image_public_id)
                values (?, ?, 1, 'DRAFT', 'Course', 'GRAMMAR', 'A1', 0,
                        current_timestamp, current_timestamp, 'https://example.test/cover.jpg', 'cover-1')
                """, courseId, teacherId);

        assertThatThrownBy(() -> jdbc.update("""
                update course_revisions set cover_image_public_id = null
                where course_id = ?
                """, courseId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void courseHasOneCurrentCoverReviewSnapshotAndCanReplaceIt() {
        long courseId = createCourse();
        long teacherId = teacherIdFor(courseId);
        long firstRequest = createCoverRequest(courseId, teacherId, "first");
        long secondRequest = createCoverRequest(courseId, teacherId, "second");
        jdbc.update("""
                insert into course_pending_review_snapshots
                    (course_id, snapshot_kind, cover_review_id)
                values (?, 'COVER_ONLY', ?)
                """, courseId, firstRequest);

        jdbc.update("update course_cover_reviews set status = 'SUPERSEDED' where id = ?", firstRequest);
        jdbc.update("""
                update course_pending_review_snapshots
                set cover_review_id = ?, snapshot_kind = 'COVER_ONLY'
                where course_id = ?
                """, secondRequest, courseId);

        assertThat(jdbc.queryForObject("""
                select cr.status
                from course_pending_review_snapshots prs
                join course_cover_reviews cr on cr.id = prs.cover_review_id
                where prs.course_id = ?
                """, String.class, courseId))
                .isEqualTo("PENDING_REVIEW");
        assertThatThrownBy(() -> jdbc.update("""
                insert into course_pending_review_snapshots
                    (course_id, snapshot_kind, cover_review_id)
                values (?, 'COVER_ONLY', ?)
                """, courseId, firstRequest))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migrationAllowsCourseCoverReviewNotifications() {
        long courseId = createCourse();
        long teacherId = teacherIdFor(courseId);

        assertThatCode(() -> jdbc.update("""
                insert into user_notifications
                    (id, user_id, type, course_id, dedupe_key, message)
                values (?, ?, 'COURSE_COVER_REVIEW_APPROVED', ?, ?, 'Cover approved')
                """, UUID.randomUUID(), teacherId, courseId, "cover-approved-" + courseId))
                .doesNotThrowAnyException();
    }

    @Test
    @Transactional
    void adminQueuePagesOnlyRegistryCurrentRequestsOldestFirst() {
        jdbc.update("delete from course_pending_review_snapshots where snapshot_kind = 'COVER_ONLY'");
        long oldestCourse = createCourse();
        long newestCourse = createCourse();
        long staleCourse = createCourse();
        long oldest = createCoverRequest(oldestCourse, teacherIdFor(oldestCourse), "queue-oldest");
        long newest = createCoverRequest(newestCourse, teacherIdFor(newestCourse), "queue-newest");
        long stale = createCoverRequest(staleCourse, teacherIdFor(staleCourse), "queue-stale");
        jdbc.update("update course_cover_reviews set submitted_at = timestamp '2026-01-01 00:00:00' where id = ?", oldest);
        jdbc.update("update course_cover_reviews set submitted_at = timestamp '2026-02-01 00:00:00' where id = ?", newest);
        jdbc.update("update course_cover_reviews set submitted_at = timestamp '2026-03-01 00:00:00' where id = ?", stale);
        registerCoverSnapshot(oldestCourse, oldest);
        registerCoverSnapshot(newestCourse, newest);

        var firstPage = adminCoverReviews.listPending(1, null);
        var secondPage = adminCoverReviews.listPending(1, firstPage.nextCursor());

        assertThat(firstPage.content()).extracting("id").containsExactly(oldest);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(secondPage.content()).extracting("id").containsExactly(newest);
        assertThat(secondPage.hasNext()).isFalse();
    }

    private long createCourse() {
        String suffix = UUID.randomUUID().toString();
        jdbc.update("""
                insert into users (email, password_hash, full_name, role)
                values (?, 'hash', 'Cover Teacher', 'TEACHER')
                """, "cover-moderation-" + suffix + "@example.com");
        long teacherId = jdbc.queryForObject(
                "select id from users where email = ?", Long.class,
                "cover-moderation-" + suffix + "@example.com");
        jdbc.update("""
                insert into courses (teacher_id, name, type, level, order_index, status)
                values (?, ?, 'GRAMMAR', 'A1', 0, 'DRAFT')
                """, teacherId, "Cover Course " + suffix);
        return jdbc.queryForObject(
                "select id from courses where name = ?", Long.class,
                "Cover Course " + suffix);
    }

    private long teacherIdFor(long courseId) {
        return jdbc.queryForObject("select teacher_id from courses where id = ?", Long.class, courseId);
    }

    private long createCoverRequest(long courseId, long teacherId, String suffix) {
        jdbc.update("""
                insert into course_cover_reviews
                    (course_id, teacher_id, image_url, image_public_id, status, submitted_at)
                values (?, ?, ?, ?, 'PENDING_REVIEW', current_timestamp)
                """, courseId, teacherId, "https://example.test/" + suffix + ".jpg", "cover-" + suffix);
        return jdbc.queryForObject(
                "select max(id) from course_cover_reviews where course_id = ?", Long.class, courseId);
    }

    private void registerCoverSnapshot(long courseId, long reviewId) {
        jdbc.update("""
                insert into course_pending_review_snapshots(course_id, snapshot_kind, cover_review_id)
                values (?, 'COVER_ONLY', ?)
                """, courseId, reviewId);
    }
}
