package com.learne.backend.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(Redis redis, Ttl ttl) {

    public CacheProperties {
        redis = redis == null ? new Redis(true, "learne", 10) : redis;
        ttl = ttl == null ? new Ttl(null, null, null, null, null, null, null, null) : ttl;
    }

    public record Redis(Boolean enabled, String keyPrefix, Integer ttlJitterPercent) {
        public Redis {
            enabled = enabled == null ? true : enabled;
            if (keyPrefix == null || keyPrefix.isBlank()) {
                keyPrefix = "learne";
            }
            ttlJitterPercent = ttlJitterPercent == null ? 10 : ttlJitterPercent;
            ttlJitterPercent = Math.max(0, Math.min(ttlJitterPercent, 50));
        }
    }

    public record Ttl(Duration studentCatalog,
                      Duration studentCourseDetail,
                      Duration studentDashboard,
                      Duration teacherDashboard,
                      Duration adminDashboard,
                      Duration leaderboard,
                      Duration teacherPublic,
                      Duration courseAccess) {
        public Ttl {
            studentCatalog = defaultDuration(studentCatalog, Duration.ofMinutes(5));
            studentCourseDetail = defaultDuration(studentCourseDetail, Duration.ofMinutes(5));
            studentDashboard = defaultDuration(studentDashboard, Duration.ofSeconds(60));
            teacherDashboard = defaultDuration(teacherDashboard, Duration.ofSeconds(60));
            adminDashboard = defaultDuration(adminDashboard, Duration.ofSeconds(60));
            leaderboard = defaultDuration(leaderboard, Duration.ofMinutes(5));
            teacherPublic = defaultDuration(teacherPublic, Duration.ofMinutes(10));
            courseAccess = defaultDuration(courseAccess, Duration.ofSeconds(120));
        }

        private static Duration defaultDuration(Duration value, Duration fallback) {
            return value == null || value.isNegative() || value.isZero() ? fallback : value;
        }
    }
}
