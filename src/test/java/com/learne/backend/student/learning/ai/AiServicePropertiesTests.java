package com.learne.backend.student.learning.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AiServicePropertiesTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "app.ai-service.base-url=${AI_SERVICE_URL:http://ai-service:5000}",
                    "app.ai-service.timeout-ms=${AI_SERVICE_TIMEOUT_MS:8000}",
                    "app.ai-service.enabled=${AI_SERVICE_ENABLED:true}")
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultsBlankBaseUrlNonPositiveTimeoutAndOmittedEnabled() {
        AiServiceProperties properties = new AiServiceProperties("", 0, null);

        assertEquals("http://ai-service:5000", properties.baseUrl());
        assertEquals(8000, properties.timeoutMs());
        assertTrue(properties.enabled());
    }

    @Test
    void bindsAiServiceEnvironmentPropertyPlaceholders() {
        contextRunner
                .withPropertyValues(
                        "AI_SERVICE_URL=http://localhost:5100",
                        "AI_SERVICE_TIMEOUT_MS=1234",
                        "AI_SERVICE_ENABLED=false")
                .run(context -> {
                    AiServiceProperties properties = context.getBean(AiServiceProperties.class);

                    assertEquals("http://localhost:5100", properties.baseUrl());
                    assertEquals(1234, properties.timeoutMs());
                    assertEquals(false, properties.enabled());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiServiceProperties.class)
    static class TestConfiguration {
    }
}
