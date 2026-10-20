package com.learne.backend.jobs.service;

import com.learne.backend.jobs.config.JobProperties;
import com.learne.backend.jobs.model.JobSpec;
import com.learne.backend.jobs.model.JobLease;
import com.learne.backend.jobs.model.JobState;
import com.learne.backend.jobs.model.Ciphertext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import com.learne.backend.jobs.model.JobType;
import com.learne.backend.jobs.model.QueueName;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Service
public class JobStore implements JobEnqueuer {
    private final NamedParameterJdbcTemplate jdbc;
    private final Map<QueueName, Integer> maxAttempts;
    private final ObjectReader payloadReader;
    private JobMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    void setMetrics(JobMetrics metrics) { this.metrics = metrics; }

    public JobStore(NamedParameterJdbcTemplate jdbc, JobProperties properties, ObjectMapper mapper) {
        this.jdbc = jdbc;
        // Binding properties stay mutable; runtime policy owns only validated immutable scalars.
        var attempts = new EnumMap<QueueName, Integer>(QueueName.class);
        var lanes = properties.getLanes();
        for (QueueName queue : QueueName.values()) {
            var lane = lanes == null ? null : lanes.get(queue);
            int limit = lane == null ? 0 : lane.getMaxAttempts();
            if (limit < 1) {
                throw new IllegalArgumentException("Invalid job lane max attempts");
            }
            attempts.put(queue, limit);
        }
        this.maxAttempts = Map.copyOf(attempts);
        this.payloadReader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(JobSpec job) {
        validate(job);
        var secret = job.secret();
        var parameters = new MapSqlParameterSource()
                .addValue("id", job.id(), Types.OTHER)
                .addValue("queue", job.queue().name())
                .addValue("type", job.type().name())
                .addValue("version", job.payloadVersion())
                .addValue("priority", job.priority())
                .addValue("dedupe", job.dedupeKey())
                .addValue("payload", job.payloadJson())
                .addValue("maxAttempts", maxAttempts.get(job.queue()))
                .addValue("expiresAt", job.expiresAt() == null ? null : job.expiresAt().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("keyId", secret == null ? null : secret.keyId(), Types.VARCHAR)
                .addValue("nonce", secret == null ? null : secret.nonce(), Types.BINARY)
                .addValue("ciphertext", secret == null ? null : secret.bytes(), Types.BINARY);
        try {
            jdbc.update("""
                    INSERT INTO background_jobs(id,queue,type,payload_version,priority,dedupe_key,payload,
                        max_attempts,expires_at,secret_key_id,secret_nonce,secret_ciphertext)
                    VALUES(:id,:queue,:type,:version,:priority,:dedupe,CAST(:payload AS jsonb),
                        :maxAttempts,:expiresAt,:keyId,:nonce,:ciphertext)
                    ON CONFLICT(dedupe_key) DO NOTHING
                    """, parameters);
            // Separate statement: READ COMMITTED gets a fresh snapshot after waiting for a winner.
            return jdbc.queryForObject("SELECT id FROM background_jobs WHERE dedupe_key=:dedupe",
                    new MapSqlParameterSource("dedupe", job.dedupeKey()), UUID.class);
        } catch (DataAccessException e) {
            throw new JobEnqueueException(e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Optional<JobLease> claim(QueueName queue, Map<JobType, Set<Integer>> supported, Duration leaseDuration) {
        double seconds = leaseSeconds(leaseDuration);
        if (queue == null || supported == null) throw new IllegalArgumentException("Invalid job capabilities");
        var parameters = new MapSqlParameterSource("queue", queue.name())
                .addValue("token", UUID.randomUUID(), Types.OTHER).addValue("seconds", seconds);
        var predicates = new StringJoiner(" OR ");
        int index = 0;
        for (var entry : supported.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || entry.getValue().stream().anyMatch(v -> v == null || v < 1)) {
                throw new IllegalArgumentException("Invalid job capabilities");
            }
            if (entry.getValue().isEmpty()) continue;
            predicates.add("(type=:type" + index + " AND payload_version IN (:versions" + index + "))");
            parameters.addValue("type" + index, entry.getKey().name())
                    .addValue("versions" + index, Set.copyOf(entry.getValue()));
            index++;
        }
        if (index == 0) return Optional.empty();
        // Only generated parameter names enter the SQL; types and versions are always bound.
        String sql = """
                WITH candidate AS (
                    SELECT id, attempts >= max_attempts AS control_only FROM background_jobs
                    WHERE queue=:queue AND state IN ('READY','RETRY_WAIT')
                        AND available_at <= clock_timestamp() AND (
                """ + predicates + """
                    ) ORDER BY priority,available_at,created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE background_jobs j SET state='RUNNING',lease_token=:token,
                    lease_until=clock_timestamp()+(:seconds * interval '1 second'),
                    attempts=CASE WHEN attempts<max_attempts THEN attempts+1 ELSE attempts END,
                    updated_at=clock_timestamp()
                FROM candidate c WHERE j.id=c.id RETURNING j.*,c.control_only
                """;
        return jdbc.query(sql, parameters, (rs, row) -> {
            String key = rs.getString("secret_key_id");
            Ciphertext secret = key == null ? null : new Ciphertext(key, rs.getBytes("secret_nonce"),
                    rs.getBytes("secret_ciphertext"));
            return new JobLease(rs.getObject("id", UUID.class), rs.getObject("lease_token", UUID.class),
                    rs.getTimestamp("lease_until").toInstant(), JobType.valueOf(rs.getString("type")),
                    rs.getInt("payload_version"), rs.getInt("attempts"), rs.getInt("max_attempts"),
                    rs.getString("payload"), secret, rs.getBoolean("control_only"));
        }).stream().findFirst();
    }

    private static final String OWNED = """
             WHERE id=:id AND state='RUNNING' AND lease_token=:token AND lease_until>clock_timestamp()
            """;

    @Transactional(propagation = Propagation.REQUIRED)
    public boolean isLeaseCurrent(JobLease lease) {
        boolean current = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM background_jobs "
                + OWNED + ")", owned(lease), Boolean.class));
        if (!current && metrics != null) metrics.stale(lease);
        return current;
    }

    @Transactional(propagation = Propagation.REQUIRED, timeout = 5)
    public boolean heartbeat(JobLease lease, Duration extendBy) {
        var parameters = owned(lease).addValue("seconds", leaseSeconds(extendBy));
        boolean changed = jdbc.update("""
                UPDATE background_jobs SET lease_until=clock_timestamp()+(:seconds * interval '1 second'),
                    updated_at=clock_timestamp()
                """ + OWNED, parameters) == 1;
        if (!changed && metrics != null) metrics.stale(lease);
        return changed;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public boolean complete(JobLease lease, JobState terminal, String safeCode) {
        if (terminal != JobState.SUCCEEDED && terminal != JobState.SKIPPED && terminal != JobState.DEAD)
            throw new IllegalArgumentException("Invalid job terminal state");
        validateCode(safeCode);
        boolean changed = jdbc.update("""
                UPDATE background_jobs SET state=:state,completed_at=clock_timestamp(),
                    lease_token=NULL,lease_until=NULL,secret_key_id=NULL,secret_nonce=NULL,
                    secret_ciphertext=NULL,last_error_code=:code,updated_at=clock_timestamp()
                """ + OWNED, owned(lease).addValue("state", terminal.name()).addValue("code", safeCode, Types.VARCHAR)) == 1;
        if (metrics != null) {
            if (changed) metrics.completed(lease, terminal); else metrics.stale(lease);
        }
        return changed;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public boolean retry(JobLease lease, Instant nextAttempt, String safeCode) {
        validateCode(safeCode);
        boolean changed = jdbc.update("""
                UPDATE background_jobs SET state='RETRY_WAIT',available_at=:available,
                    lease_token=NULL,lease_until=NULL,last_error_code=:code,updated_at=clock_timestamp()
                """ + OWNED + " AND (expires_at IS NULL OR :available < expires_at)",
                owned(lease).addValue("available", available(nextAttempt), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("code", safeCode, Types.VARCHAR)) == 1;
        if (metrics != null) {
            if (changed) metrics.retried(lease);
        }
        return changed;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public boolean yield(JobLease lease, Instant nextAvailable, boolean resetAttempts) {
        return jdbc.update("""
                UPDATE background_jobs SET state='READY',available_at=:available,lease_token=NULL,lease_until=NULL,
                    attempts=CASE WHEN :reset THEN 0 ELSE attempts END,updated_at=clock_timestamp()
                """ + OWNED, owned(lease).addValue("available", available(nextAvailable), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("reset", resetAttempts)) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public int recoverExpired(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid recovery batch limit");
        // Never touch domain rows while holding job locks. Exhaustion is handled by a later control lease.
        return jdbc.update("""
                WITH expired AS (
                    SELECT id FROM background_jobs WHERE state='RUNNING' AND lease_until<=clock_timestamp()
                    ORDER BY lease_until,id FOR UPDATE SKIP LOCKED LIMIT :limit
                )
                UPDATE background_jobs j SET state='READY',lease_token=NULL,lease_until=NULL,
                    available_at=clock_timestamp(),updated_at=clock_timestamp()
                FROM expired e WHERE j.id=e.id
                """, new MapSqlParameterSource("limit", limit));
    }

    private static MapSqlParameterSource owned(JobLease lease) {
        if (lease == null || lease.jobId() == null || lease.token() == null)
            throw new IllegalArgumentException("Invalid job lease");
        return new MapSqlParameterSource("id", lease.jobId()).addValue("token", lease.token(), Types.OTHER);
    }

    private static double leaseSeconds(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero())
            throw new IllegalArgumentException("Invalid job lease duration");
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }

    private static java.time.OffsetDateTime available(Instant instant) {
        if (instant == null) throw new IllegalArgumentException("Invalid job availability");
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static void validateCode(String code) {
        if (code != null && !code.matches("[A-Z0-9_]{1,64}"))
            throw new IllegalArgumentException("Invalid job safe code");
    }

    private void validate(JobSpec job) {
        if (job == null || job.id() == null || job.queue() == null || job.type() == null
                || job.payloadVersion() != 1 || job.dedupeKey() == null || job.dedupeKey().isBlank()
                || job.dedupeKey().length() > 255 || job.dedupeKey().indexOf('\0') >= 0
                || job.payloadJson() == null || job.payloadJson().getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException("Invalid job envelope");
        }
        QueueName expectedQueue = switch (job.type()) {
            case COURSE_ENRICHMENT -> QueueName.AI;
            case REGISTRATION_OTP_MAIL, PASSWORD_RESET_MAIL, TEACHER_INVITATION_MAIL -> QueueName.MAIL_AUTH;
            case PAYMENT_CONFIRMATION_MAIL, COURSE_PENDING_REVIEW_MAIL, COURSE_REVIEW_RESULT_MAIL -> QueueName.MAIL_NOTICE;
            case MEDIA_CLEANUP -> QueueName.MEDIA;
        };
        int expectedPriority = job.type() == JobType.TEACHER_INVITATION_MAIL ? 1 : 0;
        if (job.queue() != expectedQueue || job.priority() != expectedPriority) {
            throw new IllegalArgumentException("Invalid job lane or priority");
        }
        try {
            var payload = payloadReader.readTree(job.payloadJson());
            if (payload == null || !payload.isObject()) {
                throw new IllegalArgumentException("Job payload must be a JSON object");
            }
        } catch (JacksonException e) {
            // Do not retain parser diagnostics: they can include payload fragments.
            throw new IllegalArgumentException("Invalid job JSON payload");
        }
    }
}
