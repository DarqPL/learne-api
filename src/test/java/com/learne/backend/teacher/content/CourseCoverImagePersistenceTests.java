package com.learne.backend.teacher.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class CourseCoverImagePersistenceTests {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        TestDatabaseCleaner.clean(jdbc);
    }

    @Test
    void coverColumnsExistAndLegacyCourseRowsHaveNullCover() throws Exception {
        jdbc.update("""
                insert into users (email, password_hash, full_name, role)
                values ('cover-teacher@example.com', 'hash', 'Cover Teacher', 'TEACHER')
                """);
        Long teacherId = jdbc.queryForObject(
                "select id from users where email = 'cover-teacher@example.com'", Long.class);
        jdbc.update("""
                insert into courses (teacher_id, name, type, level, order_index, status)
                values (?, 'Legacy Course', 'GRAMMAR', 'A1', 0, 'DRAFT')
                """, teacherId);
        Long courseId = jdbc.queryForObject(
                "select id from courses where name = 'Legacy Course'", Long.class);

        try (Connection connection = dataSource.getConnection();
             ResultSet columns = connection.getMetaData().getColumns(null, null, "courses", null)) {
            boolean foundUrl = false;
            boolean foundPublicId = false;
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                foundUrl |= "image_url".equalsIgnoreCase(name);
                foundPublicId |= "image_public_id".equalsIgnoreCase(name);
            }
            assertThat(foundUrl).isTrue();
            assertThat(foundPublicId).isTrue();
        }

        assertThat(jdbc.queryForMap(
                "select image_url, image_public_id from courses where id = ?", courseId))
                .containsEntry("image_url", null)
                .containsEntry("image_public_id", null);
    }

    @Test
    void coverFieldsMayBothBePresentOrBothBeNull() {
        jdbc.update("""
                insert into users (email, password_hash, full_name, role)
                values ('cover-present@example.com', 'hash', 'Cover Teacher', 'TEACHER')
                """);
        Long teacherId = jdbc.queryForObject(
                "select id from users where email = 'cover-present@example.com'", Long.class);
        jdbc.update("""
                insert into courses (teacher_id, name, type, level, order_index, status)
                values (?, 'Covered Course', 'GRAMMAR', 'A1', 0, 'DRAFT')
                """, teacherId);
        Long courseId = jdbc.queryForObject(
                "select id from courses where name = 'Covered Course'", Long.class);

        jdbc.update("update courses set image_url = ?, image_public_id = ? where id = ?",
                "https://example.test/cover.jpg", "learne/course-cover/test", courseId);
        assertThat(jdbc.queryForMap(
                "select image_url, image_public_id from courses where id = ?", courseId))
                .containsEntry("image_url", "https://example.test/cover.jpg")
                .containsEntry("image_public_id", "learne/course-cover/test");

        jdbc.update("update courses set image_url = null, image_public_id = null where id = ?", courseId);
        assertThat(jdbc.queryForMap(
                "select image_url, image_public_id from courses where id = ?", courseId))
                .containsEntry("image_url", null)
                .containsEntry("image_public_id", null);
    }

    @Test
    void partialCoverPairIsRejectedByDatabaseConstraint() {
        jdbc.update("""
                insert into users (email, password_hash, full_name, role)
                values ('cover-invalid@example.com', 'hash', 'Cover Teacher', 'TEACHER')
                """);
        Long teacherId = jdbc.queryForObject(
                "select id from users where email = 'cover-invalid@example.com'", Long.class);
        jdbc.update("""
                insert into courses (teacher_id, name, type, level, order_index, status)
                values (?, 'Invalid Cover Course', 'GRAMMAR', 'A1', 0, 'DRAFT')
                """, teacherId);
        Long courseId = jdbc.queryForObject(
                "select id from courses where name = 'Invalid Cover Course'", Long.class);

        assertThatThrownBy(() -> jdbc.update(
                "update courses set image_url = ? where id = ?",
                "https://example.test/cover.jpg", courseId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
