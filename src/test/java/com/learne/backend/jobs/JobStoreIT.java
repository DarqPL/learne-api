package com.learne.backend.jobs;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import com.learne.backend.jobs.model.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class JobStoreIT extends JobsPostgresTestSupport {
    @Test
    void producerStoresCorrectLanePriorityAndAttemptDefaultsForEveryType() {
        for (JobType type : JobType.values()) {
            QueueName queue = switch (type) {
                case COURSE_ENRICHMENT -> QueueName.AI;
                case REGISTRATION_OTP_MAIL, PASSWORD_RESET_MAIL, TEACHER_INVITATION_MAIL -> QueueName.MAIL_AUTH;
                case PAYMENT_CONFIRMATION_MAIL, COURSE_PENDING_REVIEW_MAIL, COURSE_REVIEW_RESULT_MAIL -> QueueName.MAIL_NOTICE;
                case MEDIA_CLEANUP -> QueueName.MEDIA;
            };
            int priority = type == JobType.TEACHER_INVITATION_MAIL ? 1 : 0;
            var job = new JobSpec(UUID.randomUUID(), queue, type, 1, priority, type.name(), "{}", null, null);
            UUID id = new TransactionTemplate(manager).execute(status -> store.enqueue(job));
            assertThat(jdbc.queryForMap("select queue,type,max_attempts,priority,payload_version,state,attempts from background_jobs where id=?", id))
                    .containsEntry("queue", queue.name()).containsEntry("type", type.name())
                    .containsEntry("max_attempts", queue == QueueName.AI ? 3 : 5)
                    .containsEntry("priority", priority).containsEntry("payload_version", 1)
                    .containsEntry("state", JobState.READY.name()).containsEntry("attempts", 0);
        }
    }

    @Test
    void migrationCreatesNativeQueueSchema() {
        assertThat(jdbc.queryForObject("select to_regclass('public.background_jobs')::text", String.class))
                .isEqualTo("background_jobs");
        assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_name='background_jobs' and column_name='payload'", String.class))
                .isEqualTo("jsonb");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name='background_jobs'", Integer.class))
                .isEqualTo(21);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_name='background_jobs' and constraint_type='FOREIGN KEY'", Integer.class))
                .isZero();
    }

    @Test
    void createsOrderedPartialIndexes() {
        assertIndex("due", "(queue, priority, available_at, created_at, id)", "READY", "RETRY_WAIT");
        assertIndex("running", "(lease_until, id)", "RUNNING");
        assertIndex("expiry", "(expires_at, id)", "secret_ciphertext IS NOT NULL");
        assertIndex("terminal", "(completed_at, id)", "SUCCEEDED", "SKIPPED", "DEAD");
    }

    @Test
    void acceptsDefaultsAndEnforcesIdentityAndDedupe() {
        UUID id = insert();
        var row = jdbc.queryForMap("select * from background_jobs where id=?", id);
        assertThat(row).containsEntry("state", "READY").containsEntry("attempts", 0);
        assertThat(((Number) row.get("priority")).intValue()).isZero();
        assertThat(row.get("available_at")).isNotNull();
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
        assertThatThrownBy(() -> jdbc.update("insert into background_jobs select * from background_jobs where id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into background_jobs(id,queue,type,payload_version,dedupe_key,payload,max_attempts) select ?,queue,type,payload_version,dedupe_key,payload,max_attempts from background_jobs where id=?", UUID.randomUUID(), id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidPayloadCountersQueueAndState() {
        UUID id = insert();
        for (String assignment : new String[]{"queue='OTHER'", "state='OTHER'", "payload_version=0", "attempts=-1", "max_attempts=0", "payload='[]'::jsonb", "payload='null'::jsonb", "payload='1'::jsonb", "type=null", "dedupe_key=null"}) {
            assertThatThrownBy(() -> jdbc.update("update background_jobs set " + assignment + " where id=?", id))
                    .as(assignment).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void enforcesLeaseStatePairing() {
        UUID id = insert();
        for (String assignment : new String[]{"state='RUNNING'", "lease_token='00000000-0000-0000-0000-000000000001'", "lease_until=clock_timestamp()", "state='RUNNING',lease_token='00000000-0000-0000-0000-000000000001'", "state='RUNNING',lease_until=clock_timestamp()"}) {
            assertThatThrownBy(() -> jdbc.update("update background_jobs set " + assignment + " where id=?", id))
                    .as(assignment).isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update("update background_jobs set state='RUNNING',lease_token=?,lease_until=clock_timestamp() where id=?", UUID.randomUUID(), id);
        assertThatThrownBy(() -> jdbc.update("update background_jobs set state='READY' where id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesCompleteSecretTripleAndTwelveByteNonce() {
        UUID id = insert();
        for (String assignment : new String[]{"secret_key_id='key'", "secret_nonce=decode('00','hex')", "secret_ciphertext=decode('01','hex')", "secret_key_id='key',secret_nonce=decode('00','hex'),secret_ciphertext=decode('01','hex')", "secret_key_id='key',secret_nonce=decode(repeat('00',12),'hex')"}) {
            assertThatThrownBy(() -> jdbc.update("update background_jobs set " + assignment + " where id=?", id))
                    .as(assignment).isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update("update background_jobs set secret_key_id='key',secret_nonce=decode(repeat('00',12),'hex'),secret_ciphertext=decode('01','hex') where id=?", id);
    }

    @Test
    void terminalStatesRequireCompletionAndActiveStatesForbidIt() {
        UUID id = insert();
        for (String state : new String[]{"SUCCEEDED", "SKIPPED", "DEAD"}) {
            assertThatThrownBy(() -> jdbc.update("update background_jobs set state=? where id=?", state, id))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("update background_jobs set state=?,completed_at=clock_timestamp() where id=?", state, id);
            assertThatThrownBy(() -> jdbc.update("update background_jobs set completed_at=null where id=?", id))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("update background_jobs set state='READY',completed_at=null where id=?", id);
        }
        for (String state : new String[]{"READY", "RETRY_WAIT"}) {
            assertThatThrownBy(() -> jdbc.update("update background_jobs set state=?,completed_at=clock_timestamp() where id=?", state, id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private UUID insert() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into background_jobs(id,queue,type,payload_version,dedupe_key,payload,max_attempts) values (?,'AI','TEST',1,?,'{}'::jsonb,3)", id, id.toString());
        return id;
    }

    private void assertIndex(String suffix, String columns, String... predicates) {
        String ddl = jdbc.queryForObject("select indexdef from pg_indexes where schemaname='public' and indexname=?", String.class, "idx_background_jobs_" + suffix);
        assertThat(ddl).contains(columns).contains("WHERE").contains(predicates);
    }
}
