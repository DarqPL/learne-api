package com.learne.backend.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class CacheConfigTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestCachePropertiesConfiguration.class);

    private final ApplicationContextRunner cacheContextRunner = new ApplicationContextRunner()
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
    void providesFallbackCacheManagerWhenRedisDisabled() {
        cacheContextRunner
                .withPropertyValues("app.cache.redis.enabled=false")
                .run(context -> {
                    CacheManager cacheManager = context.getBean(CacheManager.class);

                    assertThat(cacheManager.getCache(CacheNames.STUDENT_DASHBOARD)).isNotNull();
                    assertThat(cacheManager).isInstanceOf(ConcurrentMapCacheManager.class);
                    assertThat(((ConcurrentMapCacheManager) cacheManager).isAllowNullValues()).isFalse();
                });
    }

    @Test
    void cacheErrorHandlerSwallowsReadErrorsAndRethrowsMutationErrors() {
        cacheContextRunner
                .withPropertyValues("app.cache.redis.enabled=false")
                .run(context -> {
                    CacheErrorHandler errorHandler = context.getBean(CacheErrorHandler.class);
                    Cache cache = new ConcurrentMapCache(CacheNames.STUDENT_DASHBOARD, false);
                    RuntimeException exception = new IllegalStateException("redis unavailable");

                    assertThatCode(() -> errorHandler.handleCacheGetError(exception, cache, "key"))
                            .doesNotThrowAnyException();
                    assertThatCode(() -> errorHandler.handleCachePutError(exception, cache, "key", "value"))
                            .doesNotThrowAnyException();
                    assertThatThrownBy(() -> errorHandler.handleCacheEvictError(exception, cache, "key"))
                            .isSameAs(exception);
                    assertThatThrownBy(() -> errorHandler.handleCacheClearError(exception, cache))
                            .isSameAs(exception);
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
    static class TestCachePropertiesConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CacheConfig.class)
    static class TestCacheConfiguration {
    }
}
