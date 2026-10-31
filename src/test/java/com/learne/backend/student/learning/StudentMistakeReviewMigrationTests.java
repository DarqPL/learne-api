package com.learne.backend.student.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class StudentMistakeReviewMigrationTests {
    @Test
    void h2MigrationCreatesChronologyAndReviewSchema() throws Exception {
        String databaseName = "mistake_review_migration_"
                + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        Connection connection = DriverManager.getConnection(url, "sa", "");
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration-h2")
                .target("27")
                .load()
                .migrate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'question_answers' and column_name = 'answer_event_order'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_name in ('mistake_review_sessions',
                                     'mistake_review_session_questions',
                                     'mistake_review_answers')
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void h2MigrationBackfillsAnswerEventsBySubmissionTimeThenAnswerId() throws Exception {
        String databaseName = "mistake_review_backfill_"
                + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        Connection connection = DriverManager.getConnection(url, "sa", "");
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-h2")
                .target("6").load().migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("insert into users (id,email,password_hash,full_name,role) values (1,'s@e.test','hash','Student','STUDENT')");
        jdbcTemplate.update("insert into courses (id,teacher_id,name,type,level,order_index,status) values (1,1,'Course','COMPREHENSIVE','A1',1,'APPROVED')");
        jdbcTemplate.update("insert into topics (id,course_id,name,order_index) values (1,1,'Topic',1)");
        jdbcTemplate.update("insert into lessons (id,topic_id,teacher_id,title,content,skill_type,order_index,xp_reward,is_placement_test,status) values (1,1,1,'Lesson','Content','SPEAKING',1,1,false,'APPROVED')");
        jdbcTemplate.update("insert into questions (id,lesson_id,teacher_id,type,question_text,order_index,xp_reward,status) values (1,1,1,'SHORT_ANSWER','Q1',1,1,'APPROVED'), (2,1,1,'SHORT_ANSWER','Q2',2,1,'APPROVED')");
        jdbcTemplate.update("insert into course_enrollments (id,user_id,course_id,status,progress_percent,enrolled_at) values (1,1,1,'ACTIVE',0,CURRENT_TIMESTAMP)");
        jdbcTemplate.update("insert into quiz_attempts (id,user_id,course_enrollment_id,lesson_id,status,score_percent,earned_xp,passed,submitted_at) values (1,1,1,1,'SUBMITTED',0,0,false,?)", Timestamp.valueOf("2026-01-02 00:00:00"));
        jdbcTemplate.update("insert into quiz_attempts (id,user_id,course_enrollment_id,lesson_id,status,score_percent,earned_xp,passed,submitted_at) values (2,1,1,1,'SUBMITTED',0,0,false,?)", Timestamp.valueOf("2026-01-01 00:00:00"));
        jdbcTemplate.update("insert into question_answers (id,quiz_attempt_id,question_id,correct,score_percent,earned_xp,grading_method) values (20,1,1,false,0,0,'EXACT_MATCH'), (10,2,2,false,0,0,'EXACT_MATCH')");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-h2")
                .target("27").load().migrate();

        assertThat(jdbcTemplate.queryForList(
                "select answer_event_order from question_answers order by answer_event_order", Long.class))
                .containsExactly(1L, 2L);
        assertThat(jdbcTemplate.queryForObject(
                "select answer_event_order from question_answers where id = 10", Long.class))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select answer_event_order from question_answers where id = 20", Long.class))
                .isEqualTo(2L);
    }
}
