package com.learne.backend.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CacheConfigTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestCacheConfiguration.class);

    @Test
    void bindsCacheProperties() {
        contextRunner
                .withPropertyValues(
                        "app.cache.redis.enabled=false",
                        "app.cache.ttl.student-dashboard=45s",
                        "app.cache.ttl.student-catalog=3m")
                .run(context -> {
                    CacheProperties cacheProperties = context.getBean(CacheProperties.class);

                    assertThat(cacheProperties.redis().enabled()).isFalse();
                    assertThat(cacheProperties.ttl().studentDashboard().getSeconds()).isEqualTo(45);
                    assertThat(cacheProperties.ttl().studentCatalog().toMinutes()).isEqualTo(3);
                });
    }

    @Test
    void redisPartialBindingKeepsDefaultsForMissingValues() {
        contextRunner
                .withPropertyValues("app.cache.redis.key-prefix=custom-prefix")
                .run(context -> {
                    CacheProperties properties = context.getBean(CacheProperties.class);

                    assertThat(properties.redis().enabled()).isTrue();
                    assertThat(properties.redis().keyPrefix()).isEqualTo("custom-prefix");
                    assertThat(properties.redis().ttlJitterPercent()).isEqualTo(10);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CacheProperties.class)
    static class TestCacheConfiguration {
    }
}
