package com.learne.backend.jobs;

import static org.assertj.core.api.Assertions.*;

import com.learne.backend.jobs.config.JobProperties;
import com.learne.backend.jobs.model.JobSpec;
import com.learne.backend.jobs.model.JobType;
import com.learne.backend.jobs.model.QueueName;
import com.learne.backend.jobs.service.JobEnqueueException;
import com.learne.backend.jobs.service.JobStore;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.ObjectMapper;

class JobStoreTests {
    @Test
    void insertAndSelectFailuresNeverExposeRawJdbcDiagnostics() {
        String marker = "PRIVATE_JOB_PAYLOAD_87c249";
        var sql = new SQLException("DETAIL: " + marker, "22P05");
        sql.setNextException(new SQLException("CONTEXT: " + marker));
        var raw = new DataIntegrityViolationException("SQL parameters: " + marker, sql);
        raw.addSuppressed(new IllegalStateException("suppressed: " + marker));
        for (boolean failSelect : new boolean[]{false, true}) {
            var jdbc = new StubJdbc();
            jdbc.failure = raw;
            jdbc.failSelect = failSelect;
            var store = new JobStore(jdbc, new JobProperties(), new ObjectMapper());
            Throwable failure = catchThrowable(() -> store.enqueue(media()));
            assertThat(failure).isExactlyInstanceOf(JobEnqueueException.class)
                    .hasMessage("QUEUE_WRITE_UNAVAILABLE");
            var printed = new StringWriter();
            failure.printStackTrace(new PrintWriter(printed));
            assertThat(printed.toString()).doesNotContain(marker, "DETAIL:", "CONTEXT:", "SQL parameters:");
            assertThat(failure.toString()).doesNotContain(marker);
            assertThat(failure.getMessage()).doesNotContain(marker);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
        }
    }

    @Test
    void inputMapAndLaneMutationsCannotChangeReadyStoreAttempts() {
        var properties = new JobProperties();
        var input = new EnumMap<>(properties.getLanes());
        input.put(QueueName.MEDIA, new JobProperties.Lane(9));
        properties.setLanes(input);
        var jdbc = new StubJdbc();
        var store = new JobStore(jdbc, properties, new ObjectMapper());
        input.get(QueueName.MEDIA).setMaxAttempts(77);
        store.enqueue(media());
        assertThat(jdbc.attempts).isEqualTo(9);
        input.clear();
        store.enqueue(media());
        assertThat(jdbc.attempts).isEqualTo(9);
    }

    @Test
    void getterAndSetterMutationsCannotChangeReadyStoreAttempts() {
        var properties = new JobProperties();
        properties.getLanes().get(QueueName.MEDIA).setMaxAttempts(8);
        var jdbc = new StubJdbc();
        var store = new JobStore(jdbc, properties, new ObjectMapper());
        properties.getLanes().get(QueueName.MEDIA).setMaxAttempts(0);
        store.enqueue(media());
        assertThat(jdbc.attempts).isEqualTo(8);
        properties.getLanes().clear();
        properties.setLanes(null);
        store.enqueue(media());
        assertThat(jdbc.attempts).isEqualTo(8);
    }

    @Test
    void refusesIncompleteOrInvalidRuntimeAttemptConfiguration() {
        for (int attempts : new int[]{0, -1}) {
            var properties = new JobProperties();
            properties.getLanes().get(QueueName.AI).setMaxAttempts(attempts);
            assertThatThrownBy(() -> new JobStore(new StubJdbc(), properties, new ObjectMapper()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var missing = new JobProperties();
        missing.getLanes().remove(QueueName.MAIL_AUTH);
        assertThatThrownBy(() -> new JobStore(new StubJdbc(), missing, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class);
        var nullLane = new JobProperties();
        nullLane.getLanes().put(QueueName.MEDIA, null);
        assertThatThrownBy(() -> new JobStore(new StubJdbc(), nullLane, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class);
        var nullMap = new JobProperties();
        nullMap.setLanes(null);
        assertThatThrownBy(() -> new JobStore(new StubJdbc(), nullMap, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static JobSpec media() {
        return new JobSpec(UUID.randomUUID(), QueueName.MEDIA, JobType.MEDIA_CLEANUP, 1, 0,
                "unit-test", "{}", null, null);
    }

    private static class StubJdbc extends NamedParameterJdbcTemplate {
        private DataAccessException failure;
        private boolean failSelect;
        private int attempts;

        StubJdbc() {
            super(new JdbcTemplate());
        }

        @Override
        public int update(String sql, SqlParameterSource parameters) {
            if (failure != null && !failSelect) throw failure;
            attempts = (Integer) parameters.getValue("maxAttempts");
            return 1;
        }

        @Override
        public <T> T queryForObject(String sql, SqlParameterSource parameters, Class<T> requiredType) {
            if (failure != null) throw failure;
            return requiredType.cast(UUID.randomUUID());
        }
    }
}
