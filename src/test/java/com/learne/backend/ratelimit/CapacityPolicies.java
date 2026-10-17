package com.learne.backend.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Map;

final class CapacityPolicies {
    static final String DEPLETED = "d".repeat(48);
    static final String ALLOWED = "a".repeat(48);
    static final RateLimitProperties PROPERTIES = new RateLimitProperties(true, "n".repeat(32), "e".repeat(16),
            RateLimitProperties.LOCAL_TEST_SECRET, false, List.of(), Map.of(
                    DEPLETED, new RateLimitPolicy(RateLimitDimension.USER, "r999999999", 1, 1, Duration.ofDays(1)),
                    ALLOWED, new RateLimitPolicy(RateLimitDimension.USER, "r999999999", 1, 1, Duration.ofSeconds(1))));
    static String key(int user) {
        return new RateLimitKeyFactory(PROPERTIES).key(DEPLETED, PROPERTIES.policies().get(DEPLETED),
                RateLimitDimension.USER, Integer.toString(user));
    }
}
