package com.learne.backend.teacher.content.submission;

import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.teacher.content.enrichment.CourseReviewEnrichmentService;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Registered explicitly with the real completion port when course submission workers are enabled. */
public final class CourseSubmissionHandler implements JobHandler {
    private final SubmissionCheckpointService checkpoints;
    private final CourseReviewEnrichmentService enrichment;
    private final SubmissionCompletionPort completion;
    private final ObjectMapper mapper;
    public CourseSubmissionHandler(SubmissionCheckpointService checkpoints,CourseReviewEnrichmentService enrichment,
            SubmissionCompletionPort completion,ObjectMapper mapper) {
        this.checkpoints=checkpoints; this.enrichment=enrichment; this.completion=java.util.Objects.requireNonNull(completion); this.mapper=mapper;
    }
    public JobType type() { return JobType.COURSE_ENRICHMENT; }
    public int payloadVersion() { return 1; }
    public void execute(JobLease lease) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Worker requires no ambient transaction");
        var id=id(lease); var turn=databaseTurn(() -> checkpoints.load(id));
        if(turn.rows().isEmpty()) { databaseTurn(() -> { completion.complete(lease,id); return null; }); return; }
        var results=enrichment.enrichBatch(turn.context(),turn.parent().contract());
        databaseTurn(() -> { checkpoints.checkpoint(lease,turn,results); return null; });
    }
    /** Catch outside the transactional proxy, after rollback/connection cleanup. No local retry loop.
     * The dispatcher retains its finite attempt budget/backoff and current-lease checks.
     */
    private static <T> T databaseTurn(Supplier<T> operation) {
        try { return operation.get(); }
        catch (JobEnqueueException | TransientDataAccessException | RecoverableDataAccessException
                | CannotGetJdbcConnectionException | TransactionTimedOutException transientFailure) {
            throw new RetryableJobException("BUSY",null);
        } catch (org.springframework.jdbc.UncategorizedSQLException translatedFailure) {
            // PostgreSQL lock_not_available can escape Spring's SQL-state translation as uncategorized.
            // Inspect only this known JDBC wrapper after the transaction proxy returns. Never unwrap
            // arbitrary JPA/rollback exceptions whose commit/rollback outcome may be unknown.
            if ("55P03".equals(translatedFailure.getSQLException().getSQLState()))
                throw new RetryableJobException("BUSY",null);
            throw translatedFailure;
        } catch (CannotCreateTransactionException creationFailure) {
            // Configuration/programming failures to begin a transaction are not automatically transient.
            Throwable cause=creationFailure.getCause();
            for(int depth=0;cause!=null && depth<8;depth++,cause=cause.getCause()) {
                if(cause instanceof TransientDataAccessException || cause instanceof RecoverableDataAccessException
                        || cause instanceof CannotGetJdbcConnectionException || cause instanceof java.sql.SQLTransientException
                        || cause instanceof java.sql.SQLRecoverableException
                        || (cause instanceof java.sql.SQLException sql && sql.getSQLState()!=null && sql.getSQLState().startsWith("08")))
                    throw new RetryableJobException("BUSY",null);
            }
            throw creationFailure;
        }
    }
    public void failPermanently(JobLease lease,String code) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Worker requires no ambient transaction");
        var id=id(lease);
        // Failed terminalization is left to the dispatcher's fenced lease recovery, never retried locally.
        databaseTurn(() -> { completion.fail(lease,id,code); return null; });
    }
    private UUID id(JobLease lease) {
        try {
            var node=mapper.readTree(lease.payloadJson());
            if(lease.type()!=type() || lease.payloadVersion()!=1 || !node.isObject() || node.size()!=1 || !node.path("submissionId").isString())
                throw new IllegalArgumentException();
            String text=node.path("submissionId").asString(); var id=UUID.fromString(text);
            if(!id.toString().equals(text)) throw new IllegalArgumentException();
            return id;
        } catch(Exception invalid) { throw new PermanentJobException("HANDLER_ERROR"); }
    }
}
