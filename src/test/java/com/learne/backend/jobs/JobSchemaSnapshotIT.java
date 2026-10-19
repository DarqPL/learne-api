package com.learne.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Each class receives a fresh empty native database, migrated by Spring's Flyway startup. */
class JobSchemaSnapshotIT extends JobsPostgresTestSupport {
    @Test
    void migratesEmptyDatabaseAndChecksCanonicalSchemaArtifact() throws Exception {
        var tables = jdbc.queryForList("select tablename from pg_tables where schemaname='public'", String.class);
        assertThat(tables).contains("users", "refresh_tokens", "courses", "course_revisions", "lessons",
                "orders", "order_items", "subscriptions", "sepay_webhook_events", "background_jobs", "course_review_decisions",
                "course_submission_attempts", "course_submission_questions", "user_notifications");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=true and version='24'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=true and version='25'", Integer.class))
                .isEqualTo(1);

        Path snapshot = Path.of("database", "schema.sql");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=true and version='26'", Integer.class))
                .isEqualTo(1);
        if (Boolean.getBoolean("jobs.schema.export")) {
            var dump = postgres.execInContainer("pg_dump", "-U", "jobs_test", "-d", "jobs_it",
                    "--schema-only", "--no-owner", "--no-privileges", "--exclude-table=flyway_schema_history");
            assertThat(dump.getExitCode()).as(dump.getStderr()).isZero();
            String schema = dump.getStdout().replaceAll("(?m)^\\\\(?:un)?restrict[^\\r\\n]*(?:\\r?\\n|$)", "");
            assertCanonical(schema);
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, schema, StandardCharsets.UTF_8);
        }
        assertThat(snapshot).isRegularFile();
        assertCanonical(Files.readString(snapshot, StandardCharsets.UTF_8));
    }

    private void assertCanonical(String schema) {
        assertThat(schema).contains("CREATE TABLE public.users", "CREATE TABLE public.refresh_tokens",
                        "CREATE TABLE public.courses", "CREATE TABLE public.course_revisions",
                        "CREATE TABLE public.lessons", "CREATE TABLE public.orders", "CREATE TABLE public.order_items",
                        "CREATE TABLE public.subscriptions", "CREATE TABLE public.sepay_webhook_events",
                         "CREATE TABLE public.background_jobs", "idx_background_jobs_due", "background_jobs_lease_check",
                         "CREATE TABLE public.course_review_decisions", "mail_generation uuid", "invitation_generation uuid",
                          "uq_review_decision_submission", "idx_review_decision_revision", "idx_invitation_mail_recipient_generation",
                          "CREATE TABLE public.course_submission_attempts", "CREATE TABLE public.course_submission_questions",
                          "CREATE TABLE public.user_notifications", "uq_course_submission_active", "idx_course_submission_latest",
                          "idx_course_submission_terminal", "idx_user_notifications_page", "idx_user_notifications_unread",
                          "idx_user_notifications_retention")
                .doesNotContain("\\restrict", "\\unrestrict", "flyway_schema_history", "isolated-jobs-test-password",
                        "jobs_test", "OWNER TO", "GRANT ", "REVOKE ", "INSERT INTO", "COPY public.", "jdbc:postgresql:");
    }
}
