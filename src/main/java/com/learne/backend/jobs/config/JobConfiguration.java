package com.learne.backend.jobs.config;

import com.learne.backend.jobs.service.JobDispatcher;
import com.learne.backend.jobs.service.JobHandler;
import com.learne.backend.jobs.service.JobRegistry;
import com.learne.backend.jobs.service.JobStore;
import com.learne.backend.jobs.service.JobMetrics;
import com.learne.backend.jobs.service.JobQueueAdmission;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JobConfiguration {
    /** Explicit activation also requires C7's real atomic domain finalizer; absent port fails startup. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name="app.jobs.course-submission-enabled",havingValue="true")
    com.learne.backend.teacher.content.submission.CourseSubmissionHandler courseSubmissionHandler(
            com.learne.backend.teacher.content.submission.SubmissionCheckpointService checkpoints,
            com.learne.backend.teacher.content.enrichment.CourseReviewEnrichmentService enrichment,
            com.learne.backend.teacher.content.submission.SubmissionCompletionPort completion,
            tools.jackson.databind.ObjectMapper mapper) {
        return new com.learne.backend.teacher.content.submission.CourseSubmissionHandler(checkpoints,enrichment,completion,mapper);
    }

    @Bean
    JobProperties.Snapshot jobRuntimePolicy(JobProperties properties) { return properties.snapshot(); }

    @Bean
    JobRegistry jobRegistry(List<JobHandler> handlers) { return new JobRegistry(handlers); }

    @Bean(destroyMethod = "close")
    JobMetrics jobMetrics(MeterRegistry meters, JdbcTemplate jdbc, JobRegistry registry, JobProperties.Snapshot policy) {
        return new JobMetrics(meters, jdbc, registry, policy);
    }

    @Bean(destroyMethod = "close")
    JobDispatcher jobDispatcher(JobStore store, JobRegistry registry, JobProperties.Snapshot policy, JobMetrics metrics,
                                ObjectProvider<JobQueueAdmission> admissions) {
        var dispatcher = new JobDispatcher(store, registry, policy, Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble(), metrics, admissions.orderedStream().toList());
        if (policy.workerEnabled()) dispatcher.start();
        return dispatcher;
    }
}
