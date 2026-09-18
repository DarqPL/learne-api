package com.learne.backend.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                        .filter(origin -> origin != null && !origin.isBlank())
                        .map(String::trim)
                        .toList();
        if (allowedOrigins.contains("*")) {
            throw new IllegalArgumentException("Wildcard CORS origins are not allowed when credentials are enabled");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
    }
}
