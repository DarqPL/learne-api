package com.learne.backend.ratelimit;

import io.lettuce.core.RedisURI;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {
    private static final Logger log = LoggerFactory.getLogger(RateLimitConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "app.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
    @EnableConfigurationProperties(DataRedisProperties.class)
    static class TransportConfiguration {
        @Bean(destroyMethod = "close")
        @DependsOn("rateLimitConfigurationValidator")
        LimiterRedisConnection limiterRedisConnection(DataRedisProperties properties,
                ObjectProvider<DataRedisConnectionDetails> details) {
            return new LimiterRedisConnection(limiterRedisUri(properties, details.getIfAvailable()));
        }

        @Bean
        TokenBucketStore tokenBucketStore(LimiterRedisConnection connection) {
            return new RedisTokenBucketStore(connection);
        }
    }

    /** Standalone endpoint only; never silently substitute the cache host for an override. */
    static RedisURI limiterRedisUri(DataRedisProperties properties, DataRedisConnectionDetails details) {
        try {
            if (properties.getCluster() != null || properties.getSentinel() != null
                    || properties.getMasterreplica() != null || hasText(properties.getUrl())
                    || hasText(properties.getSsl().getBundle())) throw unsupportedRedis();
            String host = properties.getHost();
            int port = properties.getPort();
            int database = properties.getDatabase();
            String username = properties.getUsername();
            String password = properties.getPassword();
            if (details != null) {
                if (details.getCluster() != null || details.getSentinel() != null || details.getMasterReplica() != null
                        || details.getSslBundle() != null || details.getStandalone() == null) throw unsupportedRedis();
                var standalone = details.getStandalone();
                host = standalone.getHost();
                port = standalone.getPort();
                database = standalone.getDatabase();
                username = details.getUsername();
                password = details.getPassword();
            }
            var builder = RedisURI.Builder.redis(host, port).withDatabase(database)
                    .withSsl(properties.getSsl().isEnabled());
            if (username != null && !username.isEmpty()) builder.withAuthentication(username, password == null ? "" : password);
            else if (password != null && !password.isEmpty()) builder.withPassword(password);
            return builder.build();
        } catch (RuntimeException failure) {
            throw unsupportedRedis();
        }
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    private static IllegalStateException unsupportedRedis() {
        return new IllegalStateException("Unsupported limiter Redis configuration");
    }

    @Bean
    RateLimitKeyFactory rateLimitKeyFactory(RateLimitProperties properties) {
        return new RateLimitKeyFactory(properties);
    }

    @Bean
    ClientIpResolver clientIpResolver(RateLimitProperties properties, RateLimitMetrics metrics) {
        return new ClientIpResolver(properties.trustedProxies(), metrics::clientIpFallback);
    }

    @Bean(destroyMethod = "close")
    RateLimitMetrics rateLimitMetrics(RateLimitProperties properties, ObjectProvider<MeterRegistry> registries) {
        MeterRegistry registry = registries.getIfAvailable();
        return new RateLimitMetrics(registry == null ? new SimpleMeterRegistry() : registry,
                properties.policies().keySet(), registry == null);
    }

    @Bean
    RateLimitService rateLimitService(RateLimitProperties properties, RateLimitKeyFactory keyFactory,
            ObjectProvider<TokenBucketStore> stores, RateLimitMetrics metrics) {
        return new RateLimitService(properties, keyFactory, stores::getObject, metrics);
    }

    @Bean
    InitializingBean rateLimitConfigurationValidator(RateLimitProperties properties, Environment environment) {
        return () -> {
            validate(properties, environment);
            if (!properties.enabled()) {
                log.warn("Rate limiting explicitly bypassed: environment={}, allow-production-bypass={}",
                        properties.environment(), properties.allowProductionBypass());
            }
        };
    }

    private static void validate(RateLimitProperties properties, Environment environment) {
        boolean prodProfile = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        boolean prodEnvironment = properties.environment().equals("prod");
        // Validate before any local/disabled early return, including production bypass.
        for (String cidr : properties.trustedProxies()) {
            TrustedProxyRange range = TrustedProxyRange.parse(cidr);
            if ((prodProfile || prodEnvironment) && range.isTrustAll()) {
                throw unsafe("production trusted proxies must not include a trust-all CIDR");
            }
        }
        if (prodProfile && !prodEnvironment) {
            throw unsafe("app.rate-limit.environment must be prod for the prod profile");
        }
        if (!prodEnvironment) {
            return;
        }
        if (!properties.enabled()) {
            if (!properties.allowProductionBypass()) {
                throw unsafe("disabled production rate limiting requires app.rate-limit.allow-production-bypass");
            }
            return;
        }
        String secret = properties.hmacSecret();
        String lower = secret.toLowerCase(Locale.ROOT);
        if (secret.equals(RateLimitProperties.LOCAL_TEST_SECRET) || lower.startsWith("local-")
                || lower.startsWith("test-") || lower.startsWith("default-") || lower.startsWith("replace-with-")
                || lower.startsWith("development-") || lower.startsWith("learne-development-")
                || secret.equals(environment.getProperty("app.auth.jwt-secret"))) {
            throw unsafe("app.rate-limit.hmac-secret must be an independent production secret");
        }
    }

    private static IllegalStateException unsafe(String message) {
        return new IllegalStateException("Unsafe rate limit configuration: " + message);
    }
}
