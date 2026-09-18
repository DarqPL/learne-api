package com.learne.backend.student.learning.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai-service")
public record AiServiceProperties(String baseUrl, int timeoutMs, Boolean enabled, String internalToken) {
    public AiServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://ai-service:5000";
        }
        if (timeoutMs <= 0) {
            timeoutMs = 8000;
        }
        if (enabled == null) {
            enabled = true;
        }
        if (internalToken == null) {
            internalToken = "";
        }
    }
}
