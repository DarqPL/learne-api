package com.learne.backend.student.learning.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Supplies RestClient.Builder because this Spring Boot WebMVC setup does not
 * auto-register one in the current dependency set.
 */
@Configuration
class AiServiceConfiguration {
    @Bean
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
