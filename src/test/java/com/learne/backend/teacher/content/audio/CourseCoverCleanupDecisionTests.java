package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.learne.backend.teacher.content.audio.dto.CleanupDecision;
import com.learne.backend.teacher.content.audio.service.CourseCoverCleanupDecisionService;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CourseCoverCleanupDecisionTests {
    private static final long COURSE_ID = 17L;
    private static final String PUBLIC_ID = "learne/course-covers/candidate";
    private static final String URL = "https://example.test/candidate.jpg";

    private JdbcTemplate jdbc;
    private CourseRepository courses;
    private CourseCoverCleanupDecisionService decisions;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:cover-cleanup-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table courses(
                    id bigint primary key,
                    image_url varchar(1024),
                    image_public_id varchar(255))
                """);
        jdbc.execute("""
                create table course_cover_reviews(
                    id bigint primary key,
                    course_id bigint not null,
                    image_url varchar(1024) not null,
                    image_public_id varchar(255) not null,
                    status varchar(32) not null)
                """);
        jdbc.execute("""
                create table course_revisions(
                    id bigint primary key,
                    course_id bigint not null,
                    cover_image_url varchar(1024),
                    cover_image_public_id varchar(255),
                    status varchar(32) not null)
                """);
        jdbc.execute("""
                create table course_pending_review_snapshots(
                    course_id bigint primary key,
                    snapshot_kind varchar(32) not null,
                    course_revision_id bigint,
                    cover_review_id bigint)
                """);
        jdbc.execute("""
                create table course_cover_cleanup_claims(
                    public_id varchar(255) primary key,
                    course_id bigint not null,
                    url varchar(1024),
                    status varchar(20) not null,
                    job_id uuid,
                    last_error_code varchar(100),
                    updated_at timestamp)
                """);
        jdbc.update("insert into courses(id) values (?)", COURSE_ID);
        jdbc.update("""
                insert into course_cover_cleanup_claims(public_id, course_id, url, status)
                values (?, ?, ?, 'RETAINED')
                """, PUBLIC_ID, COURSE_ID, URL);

        courses = mock(CourseRepository.class);
        when(courses.findLockedById(COURSE_ID)).thenReturn(Optional.of(new Course()));
        decisions = new CourseCoverCleanupDecisionService(courses, jdbc);
    }

    @Test
    void pendingStandaloneRequestIsRetained() {
        jdbc.update("""
                insert into course_cover_reviews(id, course_id, image_url, image_public_id, status)
                values (31, ?, ?, ?, 'PENDING_REVIEW')
                """, COURSE_ID, URL, PUBLIC_ID);
        jdbc.update("""
                insert into course_pending_review_snapshots(
                    course_id, snapshot_kind, cover_review_id)
                values (?, 'COVER_ONLY', 31)
                """, COURSE_ID);

        assertRetained();
    }

    @Test
    void publicCourseCoverIsRetainedEvenWhenItBelongsToAnotherCourse() {
        jdbc.update("insert into courses(id, image_url, image_public_id) values (18, ?, ?)", URL, PUBLIC_ID);
        when(courses.findLockedById(99L)).thenReturn(Optional.empty());

        assertThat(decisions.evaluate(99L, URL, PUBLIC_ID, UUID.randomUUID()))
                .isEqualTo(new CleanupDecision(CleanupDecision.Kind.RETAIN, "REFERENCED"));
    }

    @Test
    void editableAndRejectedRevisionCoversAreRetained() {
        for (String status : new String[] {"DRAFT", "REJECTED"}) {
            jdbc.update("""
                    insert into course_revisions(
                        id, course_id, cover_image_url, cover_image_public_id, status)
                    values (?, ?, ?, ?, ?)
                    """, status.equals("DRAFT") ? 41 : 42, COURSE_ID, URL, PUBLIC_ID, status);

            assertRetained();

            jdbc.update("delete from course_revisions where status = ?", status);
        }
    }

    @Test
    void registryCurrentPendingRevisionCoverIsRetained() {
        jdbc.update("""
                insert into course_revisions(
                    id, course_id, cover_image_url, cover_image_public_id, status)
                values (51, ?, ?, ?, 'PENDING_REVIEW')
                """, COURSE_ID, URL, PUBLIC_ID);
        jdbc.update("""
                insert into course_pending_review_snapshots(
                    course_id, snapshot_kind, course_revision_id)
                values (?, 'COURSE_REVISION', 51)
                """, COURSE_ID);

        assertRetained();
    }

    @Test
    void terminalRequestWithoutCurrentRegistryIsNotALiveReference() {
        jdbc.update("""
                insert into course_cover_reviews(id, course_id, image_url, image_public_id, status)
                values (61, ?, ?, ?, 'REJECTED')
                """, COURSE_ID, URL, PUBLIC_ID);

        assertThat(decisions.evaluate(COURSE_ID, URL, PUBLIC_ID, UUID.randomUUID()).kind())
                .isEqualTo(CleanupDecision.Kind.DELETE);
    }

    @Test
    void pendingRequestWithoutCurrentRegistryIsNotALiveReference() {
        jdbc.update("""
                insert into course_cover_reviews(id, course_id, image_url, image_public_id, status)
                values (62, ?, ?, ?, 'PENDING_REVIEW')
                """, COURSE_ID, URL, PUBLIC_ID);

        assertThat(decisions.evaluate(COURSE_ID, URL, PUBLIC_ID, UUID.randomUUID()).kind())
                .isEqualTo(CleanupDecision.Kind.DELETE);
    }

    private void assertRetained() {
        assertThat(decisions.evaluate(COURSE_ID, URL, PUBLIC_ID, UUID.randomUUID()))
                .isEqualTo(new CleanupDecision(CleanupDecision.Kind.RETAIN, "REFERENCED"));
        assertThat(jdbc.queryForObject(
                "select status from course_cover_cleanup_claims where public_id = ?",
                String.class, PUBLIC_ID)).isEqualTo("RETAINED");
    }
}
