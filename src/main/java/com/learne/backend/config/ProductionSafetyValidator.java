package com.learne.backend.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

public class ProductionSafetyValidator implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final String PROD_PROFILE = "prod";
    private static final String DEFAULT_JWT_SECRET = "learne-development-jwt-secret-change-me-please-32-bytes-minimum";
    private static final String DEFAULT_ADMIN_PASSWORD = "Admin123";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        validate(event.getEnvironment());
    }

    public void validate(ConfigurableEnvironment environment) {
        if (!Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        String jwtSecret = environment.getProperty("app.auth.jwt-secret", "");
        String adminPassword = environment.getProperty("app.admin.password", "");
        boolean refreshCookieSecure = environment.getProperty("app.auth.refresh-cookie-secure", Boolean.class, false);

        if (DEFAULT_JWT_SECRET.equals(jwtSecret) || jwtSecret.trim().length() < 32) {
            violations.add("app.auth.jwt-secret must be changed to a strong production value");
        }
        if (DEFAULT_ADMIN_PASSWORD.equals(adminPassword) || adminPassword.isBlank()) {
            violations.add("app.admin.password must be changed to a non-blank production value");
        }
        if (!refreshCookieSecure) {
            violations.add("app.auth.refresh-cookie-secure must be true in production");
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration: " + String.join("; ", violations));
        }
    }
}
