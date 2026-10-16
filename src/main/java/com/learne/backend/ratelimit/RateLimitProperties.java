package com.learne.backend.ratelimit;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(Boolean enabled, String namespace, String environment, String hmacSecret,
        Boolean allowProductionBypass, List<String> trustedProxies, Map<String, RateLimitPolicy> policies) {

    public static final String LOCAL_TEST_SECRET = "learne-rate-limit-local-test-key-not-for-production";

    public RateLimitProperties {
        enabled = enabled == null ? true : enabled;
        namespace = namespace == null ? "learne" : namespace;
        environment = environment == null ? "local" : environment;
        hmacSecret = hmacSecret == null ? LOCAL_TEST_SECRET : hmacSecret;
        allowProductionBypass = allowProductionBypass == null ? false : allowProductionBypass;
        if (!namespace.matches("[a-z][a-z0-9-]{0,31}") || !environment.matches("[a-z][a-z0-9-]{0,15}")) {
            throw new IllegalArgumentException("Invalid rate limit namespace or environment");
        }
        if (enabled) {
            int secretBytes = hmacSecret.getBytes(StandardCharsets.UTF_8).length;
            if (secretBytes < 32 || secretBytes > 256 || hmacSecret.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Rate limit HMAC secret must be 32 to 256 UTF-8 bytes without NUL");
            }
        }
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        policies = policies == null ? Map.of() : Map.copyOf(policies);
        if (policies.keySet().stream().anyMatch(id -> !id.matches("[a-z][a-z0-9-]{0,47}"))) {
            throw new IllegalArgumentException("Invalid rate limit policy ID");
        }
    }

    @Override
    public String toString() {
        return "RateLimitProperties[enabled=" + enabled + ", namespace=" + namespace + ", environment=" + environment
                + ", hmacSecret=[REDACTED], allowProductionBypass=" + allowProductionBypass
                + ", trustedProxies=" + trustedProxies + ", policies=" + policies + "]";
    }
}
