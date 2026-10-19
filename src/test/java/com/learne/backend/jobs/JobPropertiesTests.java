package com.learne.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.jobs.config.JobProperties;
import com.learne.backend.jobs.model.QueueName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JobPropertiesTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsLaneOverrideAndRetainsOtherLaneDefaults() {
        context.withPropertyValues("app.jobs.lanes.ai.max-attempts=9").run(application -> {
            assertThat(application).hasNotFailed();
            var lanes = application.getBean(JobProperties.class).getLanes();
            assertThat(lanes.get(QueueName.AI).getMaxAttempts()).isEqualTo(9);
            for (QueueName queue : new QueueName[]{QueueName.MAIL_AUTH, QueueName.MAIL_NOTICE, QueueName.MEDIA}) {
                assertThat(lanes.get(queue).getMaxAttempts()).isEqualTo(5);
            }
        });
    }

    @Test
    void rejectsNonPositiveConfiguredAttempts() {
        for (String attempts : new String[]{"0", "-1"}) {
            context.withPropertyValues("app.jobs.lanes.mail-auth.max-attempts=" + attempts)
                    .run(application -> assertThat(application).hasFailed());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobProperties.class)
    static class PropertiesConfiguration {
    }
}
