package com.learne.backend.cache;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {
    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "app.cache.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
    CacheManager redisCacheManager(RedisConnectionFactory connectionFactory, CacheProperties properties) {
        RedisSerializationContext.SerializationPair<Object> jsonSerializer = RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer());
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(jsonSerializer)
                .prefixCacheNameWith(properties.redis().keyPrefix() + ":")
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> configurations = new LinkedHashMap<>();
        configurations.put(CacheNames.STUDENT_CATALOG, defaults.entryTtl(properties.ttl().studentCatalog()));
        configurations.put(CacheNames.STUDENT_COURSE_DETAIL, defaults.entryTtl(properties.ttl().studentCourseDetail()));
        configurations.put(CacheNames.STUDENT_DASHBOARD, defaults.entryTtl(properties.ttl().studentDashboard()));
        configurations.put(CacheNames.TEACHER_DASHBOARD, defaults.entryTtl(properties.ttl().teacherDashboard()));
        configurations.put(CacheNames.ADMIN_DASHBOARD, defaults.entryTtl(properties.ttl().adminDashboard()));
        configurations.put(CacheNames.LEADERBOARD, defaults.entryTtl(properties.ttl().leaderboard()));
        configurations.put(CacheNames.TEACHER_PUBLIC, defaults.entryTtl(properties.ttl().teacherPublic()));
        configurations.put(CacheNames.COURSE_ACCESS, defaults.entryTtl(properties.ttl().courseAccess()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults.entryTtl(Duration.ofMinutes(5)))
                .withInitialCacheConfigurations(configurations)
                .transactionAware()
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.cache.redis", name = "enabled", havingValue = "false")
    CacheManager fallbackCacheManager() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager();
        cacheManager.setCacheNames(List.of(
                CacheNames.STUDENT_CATALOG,
                CacheNames.STUDENT_COURSE_DETAIL,
                CacheNames.STUDENT_DASHBOARD,
                CacheNames.TEACHER_DASHBOARD,
                CacheNames.ADMIN_DASHBOARD,
                CacheNames.LEADERBOARD,
                CacheNames.TEACHER_PUBLIC,
                CacheNames.COURSE_ACCESS));
        return cacheManager;
    }

    @Bean
    CacheErrorHandler cacheErrorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache get failed cache={} keyHash={}", cache.getName(), CacheKeySupport.logKeyHash(key), exception);
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Cache put failed cache={} keyHash={}", cache.getName(), CacheKeySupport.logKeyHash(key), exception);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.error("Cache evict failed cache={} keyHash={}", cache.getName(), CacheKeySupport.logKeyHash(key), exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.error("Cache clear failed cache={}", cache.getName(), exception);
            }
        };
    }
}
