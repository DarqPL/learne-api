package com.learne.backend.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Configuration;

class CacheConfigTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestCacheConfiguration.class)
            .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class));

    @Test
    void bindsCachePropertiesAndProvidesFallbackCacheManager() {
        contextRunner
                .withPropertyValues(
                        "app.cache.redis.enabled=false",
                        "app.cache.ttl.student-dashboard=45s",
                        "app.cache.ttl.student-catalog=3m")
                .run(context -> {
                    CacheProperties cacheProperties = context.getBean(CacheProperties.class);
                    CacheManager cacheManager = context.getBean(CacheManager.class);

                    assertThat(cacheProperties.redis().enabled()).isFalse();
                    assertThat(cacheProperties.ttl().studentDashboard().getSeconds()).isEqualTo(45);
                    assertThat(cacheProperties.ttl().studentCatalog().toMinutes()).isEqualTo(3);
                    assertThat(cacheManager.getCache(CacheNames.STUDENT_DASHBOARD)).isNotNull();
                });
    }

    @Test
    void redisPartialBindingKeepsDefaultsForMissingValues() {
        CacheProperties properties = new CacheProperties(
                new CacheProperties.Redis(null, null, null),
                null);

        assertThat(properties.redis().enabled()).isTrue();
        assertThat(properties.redis().keyPrefix()).isEqualTo("learne");
        assertThat(properties.redis().ttlJitterPercent()).isEqualTo(10);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CacheProperties.class)
    static class TestCacheConfiguration {
    }
}
