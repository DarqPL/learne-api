package com.learne.backend.teacher.content.submission;

import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.teacher.content.enrichment.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseSubmissionHandlerTests {
    @Test void postgresLockTimeoutIsRetriedOnlyFromDirectSpringJdbcTranslation() {
        var failure=new org.springframework.jdbc.UncategorizedSQLException("query","unsafe SQL",new java.sql.SQLException("unsafe SQL","55P03"));
        var checkpoints=mock(SubmissionCheckpointService.class);
        when(checkpoints.load(any())).thenThrow(failure);
        var handler=new CourseSubmissionHandler(checkpoints,mock(CourseReviewEnrichmentService.class),mock(SubmissionCompletionPort.class),new tools.jackson.databind.ObjectMapper());
        assertThatThrownBy(()->handler.execute(lease)).isInstanceOfSatisfying(RetryableJobException.class,e->{
            assertThat(e.getCode()).isEqualTo("BUSY"); assertThat(e.getCause()).isNull(); assertThat(e.getStackTrace()).isEmpty();
        });
        var port=mock(SubmissionCompletionPort.class);
        doThrow(failure).when(port).fail(lease,id,"EXHAUSTED_ATTEMPTS");
        var callback=new CourseSubmissionHandler(checkpoints,mock(CourseReviewEnrichmentService.class),port,new tools.jackson.databind.ObjectMapper());
        assertThatThrownBy(()->callback.failPermanently(lease,"EXHAUSTED_ATTEMPTS")).isInstanceOf(RetryableJobException.class);
    }

    @Test void unrelatedSqlStatesAndUnknownRollbackWrappersRemainUnchanged() {
        var lock=new java.sql.SQLException("unsafe SQL","55P03");
        var failures=new ArrayList<RuntimeException>();
        for(String state:List.of("23505","23503","42601","42P01","XX000","57014"))
            failures.add(new org.springframework.jdbc.UncategorizedSQLException("query","unsafe SQL",new java.sql.SQLException("unsafe SQL",state)));
        failures.add(new UnexpectedRollbackException("unknown rollback",lock));
        failures.add(new org.springframework.orm.jpa.JpaSystemException(new RuntimeException("rollback failed",lock)));
        failures.add(new TransactionSystemException("rollback failed",lock));
        failures.add(new DataIntegrityViolationException("integrity",lock));
        failures.add(new BadSqlGrammarException("query","unsafe SQL",lock));
        for(var failure:failures) {
            var checkpoints=mock(SubmissionCheckpointService.class); when(checkpoints.load(any())).thenThrow(failure);
            var handler=new CourseSubmissionHandler(checkpoints,mock(CourseReviewEnrichmentService.class),mock(SubmissionCompletionPort.class),new tools.jackson.databind.ObjectMapper());
            assertThatThrownBy(()->handler.execute(lease)).isSameAs(failure);
        }
    }
    private final UUID id=UUID.randomUUID();
    private final JobLease lease=new JobLease(UUID.randomUUID(),UUID.randomUUID(),Instant.now().plusSeconds(30),
            JobType.COURSE_ENRICHMENT,1,1,3,"{\"submissionId\":\""+id+"\"}",null,false);

    @Test void recognizedReadFailuresBecomeSanitizedBoundedRetries() {
        for(RuntimeException failure:List.of(new CannotAcquireLockException("unsafe SQL"),new QueryTimeoutException("unsafe SQL"),
                new CannotSerializeTransactionException("unsafe SQL"),new TransientDataAccessResourceException("unsafe SQL"),
                new RecoverableDataAccessException("unsafe SQL"),new CannotGetJdbcConnectionException("unsafe SQL"),
                new TransactionTimedOutException("unsafe SQL"),
                new CannotCreateTransactionException("unsafe SQL",new java.sql.SQLTransientConnectionException()),
                new CannotCreateTransactionException("unsafe SQL",new java.sql.SQLException("unsafe SQL","08006")))) {
            var checkpoints=mock(SubmissionCheckpointService.class); var completion=mock(SubmissionCompletionPort.class);
            var enrichment=mock(CourseReviewEnrichmentService.class);
            when(checkpoints.load(id)).thenThrow(failure);
            var handler=new CourseSubmissionHandler(checkpoints,enrichment,completion,new tools.jackson.databind.ObjectMapper());
            assertThatThrownBy(() -> handler.execute(lease)).isInstanceOfSatisfying(RetryableJobException.class,error -> {
                assertThat(error.getCode()).isEqualTo("BUSY"); assertThat(error.getRetryAfter()).isNull();
                assertThat(error.getCause()).isNull(); assertThat(error.getStackTrace()).isEmpty();
            });
            verify(checkpoints,times(1)).load(id); verifyNoInteractions(enrichment,completion);
        }
    }
    @Test void validationFencingAndUnknownFailuresAreNeverReclassified() {
        for(RuntimeException failure:List.of(new PermanentJobException("ENRICHMENT_CONTRACT_UNAVAILABLE"),
                new IllegalStateException("Stale submission lease"),new DataIntegrityViolationException("invalid staging"),
                new BadSqlGrammarException("query","query",new java.sql.SQLException()),
                new CannotCreateTransactionException("configuration"),new UnexpectedRollbackException("unknown rollback"))) {
            var checkpoints=mock(SubmissionCheckpointService.class);
            when(checkpoints.load(id)).thenThrow(failure);
            var handler=new CourseSubmissionHandler(checkpoints,mock(CourseReviewEnrichmentService.class),mock(SubmissionCompletionPort.class),new tools.jackson.databind.ObjectMapper());
            assertThatThrownBy(() -> handler.execute(lease)).isSameAs(failure);
        }
    }
    @Test void failureCallbackClassifiesOnlyKnownInfrastructureAfterProxyReturns() {
        var port=mock(SubmissionCompletionPort.class);
        var handler=new CourseSubmissionHandler(mock(SubmissionCheckpointService.class),mock(CourseReviewEnrichmentService.class),port,new tools.jackson.databind.ObjectMapper());
        doThrow(new QueryTimeoutException("unsafe SQL")).when(port).fail(lease,id,"EXHAUSTED_ATTEMPTS");
        assertThatThrownBy(() -> handler.failPermanently(lease,"EXHAUSTED_ATTEMPTS"))
                .isInstanceOfSatisfying(RetryableJobException.class,e -> { assertThat(e.getCode()).isEqualTo("BUSY"); assertThat(e.getCause()).isNull(); });
        verify(port).fail(lease,id,"EXHAUSTED_ATTEMPTS");
    }
}
