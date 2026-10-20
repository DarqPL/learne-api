package com.learne.backend.teacher.content.enrichment;

import com.learne.backend.jobs.config.JobProperties;
import com.learne.backend.student.learning.ai.AiServiceProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EnrichmentClientProperties.class)
public class EnrichmentHttpConfiguration {
    @Bean(destroyMethod = "close")
    EnrichmentHttpTransport enrichmentHttpTransport(EnrichmentClientProperties properties) {
        return new EnrichmentHttpTransport(properties);
    }

    @Bean(destroyMethod = "close")
    EnrichmentCapabilityGate enrichmentCapabilityGate(
            AiServiceProperties ai,
            EnrichmentHttpTransport transport,
            JobProperties.Snapshot policy,
            ObjectProvider<EnrichmentContractSource> sources) {
        return new EnrichmentCapabilityGate(new EnrichmentCapabilities(ai, transport), ai.enabled(),
                policy.workerEnabled(), () -> sources.orderedStream().toList());
    }
}
