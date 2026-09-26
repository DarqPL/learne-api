package com.learne.backend.teacher.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class CourseRevisionMigrationTests {
    @Test
    void migrationV12BackfillsExistingContentIntoRevisionTables() throws SQLException {
        String databaseName = "revision_migration_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + databaseName + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        Connection connection = DriverManager.getConnection(url, "sa", "");
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("11")
                .load()
                .migrate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, full_name, role)
                VALUES (1, 'teacher@example.com', 'hash', 'Teacher', 'TEACHER')
                """);
        jdbcTemplate.update("""
                INSERT INTO courses (id, teacher_id, name, description, type, level, order_index, status, rejection_reason, reviewed_by, reviewed_at, published_at)
                VALUES (10, 1, 'Legacy Course', 'Legacy Description', 'GRAMMAR', 'A1', 2, 'APPROVED', NULL, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO topics (id, course_id, name, description, order_index)
                VALUES (20, 10, 'Legacy Topic', 'Topic Description', 3)
                """);
        jdbcTemplate.update("""
                INSERT INTO lessons (id, topic_id, teacher_id, title, description, content, skill_type, audio_url, audio_public_id, order_index, xp_reward, is_placement_test, status)
                VALUES (30, 20, 1, 'Legacy Lesson', 'Lesson Description', 'Lesson Content', 'GRAMMAR', 'audio.mp3', 'audio-public-id', 4, 25, TRUE, 'APPROVED')
                """);
        jdbcTemplate.update("""
                INSERT INTO questions (id, lesson_id, teacher_id, type, question_text, explanation, correct_answer, knowledge_point, order_index, difficulty, xp_reward, status)
                VALUES (40, 30, 1, 'MULTIPLE_CHOICE', 'Question?', 'Because', 'A', 'Present simple', 5, 'EASY', 10, 'APPROVED')
                """);
        jdbcTemplate.update("""
                INSERT INTO question_options (id, question_id, option_text, is_correct, order_index)
                VALUES (50, 40, 'Option A', TRUE, 6)
                """);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("12")
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM course_revisions WHERE course_id = 10 AND revision_number = 1 AND status = 'APPROVED' AND name = 'Legacy Course'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM topic_revisions WHERE source_topic_id = 20 AND name = 'Legacy Topic'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lesson_revisions WHERE source_lesson_id = 30 AND title = 'Legacy Lesson' AND xp_reward = 25 AND is_placement_test = TRUE", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM question_revisions WHERE source_question_id = 40 AND question_text = 'Question?' AND correct_answer = 'A'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM question_option_revisions WHERE source_option_id = 50 AND option_text = 'Option A' AND is_correct = TRUE", Integer.class))
                .isEqualTo(1);
    }
}
