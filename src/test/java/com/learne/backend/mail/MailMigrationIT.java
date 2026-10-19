package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.jobs.JobsPostgresTestSupport;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class MailMigrationIT extends JobsPostgresTestSupport {
    @Test
    void installsV25ColumnsAndHistoryTable() {
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema='public' and table_name='registration_otps'", String.class))
                .contains("mail_generation");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema='public' and table_name='users'", String.class))
                .contains("invitation_generation");
    }

    @Test
    void upgradesLegacyV24WithoutGeneratingMailOrLosingData() throws Exception {
        String schema = "mail_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("24").load().migrate();
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/jobs_it",
                "jobs_test", "isolated-jobs-test-password")) {
            connection.setSchema(schema);
            var legacy = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            legacy.update("insert into users(id,email,password_hash,full_name,role) values(9001,'legacy@example.test','hash','Legacy','TEACHER')");
            legacy.update("insert into registration_otps(email,full_name,password_hash,otp_hash,expires_at) values('otp@example.test','Legacy OTP','password-hash','otp-hash',current_timestamp)");
            legacy.update("insert into courses(id,teacher_id,name,type,level,status) values(9002,9001,'Legacy course','GENERAL','A1','PENDING_REVIEW')");
            legacy.update("insert into course_revisions(id,course_id,teacher_id,revision_number,status,name,type,level) values(9003,9002,9001,1,'PENDING_REVIEW','Legacy course','GENERAL','A1')");
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("25").load().migrate();
            assertThat(legacy.queryForMap("select email,password_hash,invitation_generation from users where id=9001"))
                    .containsEntry("email", "legacy@example.test").containsEntry("password_hash", "hash")
                    .containsEntry("invitation_generation", null);
            assertThat(legacy.queryForMap("select otp_hash,mail_generation from registration_otps"))
                    .containsEntry("otp_hash", "otp-hash").containsEntry("mail_generation", null);
            assertThat(legacy.queryForObject("select count(*) from background_jobs", Integer.class)).isZero();
            insertDecision(legacy, null, "APPROVED", null);
            insertDecision(legacy, null, "REJECTED", "Historical reason");
            assertThat(legacy.queryForObject("select count(*) from course_review_decisions where submission_id is null", Integer.class)).isEqualTo(2);
            legacy.update("delete from courses where id=9002");
            legacy.update("delete from users where id=9001");
            assertThat(legacy.queryForObject("select count(*) from course_revisions", Integer.class)).isZero();
            assertThat(legacy.queryForObject("select count(*) from course_review_decisions", Integer.class)).isEqualTo(2);
            connection.setSchema("public");
        } finally {
            jdbc.execute("drop schema " + schema + " cascade");
        }
    }

    @Test
    void enforcesDecisionChecksAndNullableSubmissionUniqueness() {
        UUID submission = UUID.randomUUID();
        insertDecision(jdbc, submission, "APPROVED", null);
        assertThatThrownBy(() -> insertDecision(jdbc, submission, "APPROVED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDecision(jdbc, null, "PENDING", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDecision(jdbc, null, "REJECTED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertDecision(jdbc, null, "APPROVED", null);
        insertDecision(jdbc, null, "REJECTED", "Reason");
    }

    @Test
    void createsNativeInvitationExpressionAndRevisionIndexesWithoutHistoricalForeignKeys() {
        String index = jdbc.queryForObject("select indexdef from pg_indexes where schemaname='public' and indexname='idx_invitation_mail_recipient_generation'", String.class);
        assertThat(index).contains("USING btree", "payload ->> 'recipientUserId'", "payload ->> 'generation'",
                "created_at DESC, id DESC", "WHERE", "type", "TEACHER_INVITATION_MAIL");
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where schemaname='public' and indexname='idx_review_decision_revision'", String.class))
                .contains("(revision_id, reviewed_at, id)");
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='course_review_decisions'::regclass and contype='f'", Integer.class)).isZero();
    }

    private void insertDecision(JdbcTemplate target, UUID submission, String decision, String reason) {
        target.update("""
                insert into course_review_decisions(id,revision_id,course_id,submission_id,reviewer_id,teacher_id,
                    decision,course_name,recipient_email,rejection_reason,reviewed_at)
                values(?,9003,9002,?,9001,9001,?,'Legacy course','legacy@example.test',?,current_timestamp)
                """, UUID.randomUUID(), submission, decision, reason);
    }
}
