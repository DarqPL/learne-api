package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learne.backend.jobs.crypto.*;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.mail.config.MailJobConfiguration;
import com.learne.backend.mail.model.MailTemplate;
import com.learne.backend.mail.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MailJobConfigurationTests {
    ApplicationContextRunner runner(boolean key) {
        var mapper = JsonMapper.builder().build();
        var properties = new JobCryptoProperties();
        if (key) { properties.setActiveKeyId("v1"); properties.setKeysJson("{\"v1\":\"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=\"}"); }
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MailJobConfiguration.class))
                .withBean(ObjectMapper.class, () -> mapper)
                .withBean(JobPayloadCipher.class, () -> new JobPayloadCipher(properties, mapper))
                .withBean(JobStore.class, () -> mock(JobStore.class))
                .withBean(JobMetrics.class, () -> mock(JobMetrics.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(org.springframework.security.crypto.password.PasswordEncoder.class, BCryptPasswordEncoder::new)
                .withUserConfiguration(Registry.class);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Registry {
        @Bean JobRegistry registry(List<JobHandler> handlers) { return new JobRegistry(handlers); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Transport {
        @Bean MailTransport fakeTransport() { return message -> { throw new AssertionError("No sends in configuration test"); }; }
    }
    @Test void absentTransportLeavesMailUninstalledAndNeedsNoActiveKey() {
        runner(false).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MailJobProducer.class).doesNotHaveBean(MailEligibilityService.class);
            assertThat(context.getBeansOfType(JobHandler.class)).isEmpty();
        });
    }
    @Test void fakeTransportActivatesSixExactProductionHandlerRegistrations() {
        runner(true).withUserConfiguration(Transport.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MailJobProducer.class).hasSingleBean(MailEligibilityService.class);
            assertThat(context.getBeansOfType(JobHandler.class)).hasSize(6);
            var registry = context.getBean(JobRegistry.class);
            for (var template : MailTemplate.values()) {
                assertThat(registry.find(template.jobType(), 1)).isPresent();
                assertThat(registry.find(template.jobType(), 2)).isEmpty();
            }
            assertThat(registry.supported(QueueName.MAIL_AUTH)).hasSize(3);
            assertThat(registry.supported(QueueName.MAIL_NOTICE)).hasSize(3);
        });
    }
    @Test void activeMailConfigurationFailsStartupWithoutActiveKey() {
        runner(false).withUserConfiguration(Transport.class).run(context ->
                assertThat(context.getStartupFailure()).hasRootCauseMessage("JOB_CRYPTO_ACTIVE_KEY_REQUIRED"));
    }
}
