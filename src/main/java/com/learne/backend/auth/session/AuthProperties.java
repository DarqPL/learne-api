package com.learne.backend.auth.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        String jwtSecret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String refreshCookieName,
        String refreshCookiePath,
        String refreshCookieSameSite,
        boolean refreshCookieSecure
) {
}
