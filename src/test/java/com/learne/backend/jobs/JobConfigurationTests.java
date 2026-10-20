package com.learne.backend.jobs;

import com.learne.backend.jobs.config.JobConfiguration;
import com.learne.backend.jobs.config.JobProperties;
import com.learne.backend.jobs.service.*;
import com.learne.backend.teacher.content.enrichment.*;
import com.learne.backend.student.learning.ai.AiServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobConfigurationTests {
    @Test void enabledSubmissionWorkerWithoutCompletionPortFailsStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(Binding.class,JobConfiguration.class)
                .withBean(com.learne.backend.teacher.content.submission.SubmissionCheckpointService.class,
                        () -> mock(com.learne.backend.teacher.content.submission.SubmissionCheckpointService.class))
                .withBean(CourseReviewEnrichmentService.class,() -> mock(CourseReviewEnrichmentService.class))
                .withBean(tools.jackson.databind.ObjectMapper.class,tools.jackson.databind.ObjectMapper::new)
                .withPropertyValues("app.jobs.worker-enabled=false","app.jobs.course-submission-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class)
                            .hasStackTraceContaining("SubmissionCompletionPort");
                });
    }

    @Test void disabledWorkerReadyEventDoesNotDiscoverSourcesOrProbeRemoteAndGateIsRegistered() {
        var transport = mock(EnrichmentHttpTransport.class);
        var source = mock(EnrichmentContractSource.class);
        var discoveries = new java.util.concurrent.atomic.AtomicInteger();
        new ApplicationContextRunner()
                .withUserConfiguration(EnrichmentHttpConfiguration.class)
                .withBean(AiServiceProperties.class, () -> new AiServiceProperties(null, 8000, true, ""))
                .withBean(JobProperties.Snapshot.class, () -> {
                    var properties = new JobProperties(); properties.setWorkerEnabled(false); return properties.snapshot();
                })
                .withBean("probeTransport", EnrichmentHttpTransport.class, () -> transport, definition -> definition.setPrimary(true))
                .withBean(EnrichmentContractSource.class, () -> { discoveries.incrementAndGet(); return source; },
                        definition -> definition.setLazyInit(true))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var gate = context.getBean(EnrichmentCapabilityGate.class);
                    context.publishEvent(new org.springframework.boot.context.event.ApplicationReadyEvent(
                            new org.springframework.boot.SpringApplication(), new String[0],
                            context.getSourceApplicationContext(), java.time.Duration.ZERO));
                    assertThat(context.getBeansOfType(JobQueueAdmission.class)).containsValue(gate);
                    assertThat(gate.state()).isEqualTo(EnrichmentCapabilityGate.SafeState.PAUSED);
                    assertThat(discoveries).hasValue(0);
                    verifyNoInteractions(transport, source);
                });
    }

    @Test void disabledWorkerCreatesUsableBeansWithoutPostgresCalls() {
        var store = mock(JobStore.class);
        var handler = mock(JobHandler.class);
        when(handler.type()).thenReturn(com.learne.backend.jobs.model.JobType.COURSE_ENRICHMENT);
        when(handler.payloadVersion()).thenReturn(1);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Binding.class, JobConfiguration.class)
                .withBean(JobStore.class, () -> store)
                .withBean(JobHandler.class, () -> handler)
                .withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> mock(org.springframework.jdbc.core.JdbcTemplate.class))
                .withPropertyValues("app.jobs.worker-enabled=false", "app.jobs.lanes.ai.poll=1ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JobDispatcher.class);
                    assertThat(context).hasSingleBean(JobMetrics.class);
                    var jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
                    verify(jdbc).afterPropertiesSet();
                    verifyNoMoreInteractions(jdbc);
                    assertThat(context.getBean(JobProperties.Snapshot.class).workerEnabled()).isFalse();
                    verify(store, after(50).never()).recoverExpired(anyInt());
                    assertThat(mockingDetails(store).getInvocations()).allSatisfy(invocation ->
                            assertThat(invocation.getMethod().getName()).isEqualTo("setMetrics"));
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobProperties.class)
    static class Binding {}
}
