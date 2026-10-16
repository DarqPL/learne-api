package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.ResourcePropertySource;

class RateLimitPropertiesTests {
    private static final String SECRET = "a-strong-independent-production-key-123456";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RateLimitConfiguration.class);

    @Test
    void defaultsAreLocalAndCollectionsAreImmutable() {
        var defaults = properties(null, null, null, null, null, null);
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.namespace()).isEqualTo("learne");
        assertThat(defaults.environment()).isEqualTo("local");
        assertThat(defaults.hmacSecret()).isEqualTo("learne-rate-limit-local-test-key-not-for-production");
        assertThat(defaults.allowProductionBypass()).isFalse();
        assertThat(defaults.trustedProxies()).isEmpty();
        assertThat(defaults.policies()).isEmpty();
        var proxies = new ArrayList<>(List.of("parser-deferred-to-task3"));
        var policies = new HashMap<>(Map.of("login-ip", new RateLimitPolicy(RateLimitDimension.IP, "r1", 1, 1, Duration.ofSeconds(1))));
        var copied = properties(true, "learne", "local", SECRET, proxies, policies);
        proxies.clear();
        policies.clear();
        assertThat(copied.trustedProxies()).containsExactly("parser-deferred-to-task3");
        assertThat(copied.policies()).containsKey("login-ip");
        assertThatThrownBy(() -> copied.trustedProxies().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> copied.policies().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(copied.toString()).doesNotContain(SECRET);
    }

    @Test
    void bindsPoliciesAndAllOptionsWithLazyDedicatedTransport() {
        runner.withPropertyValues("app.rate-limit.enabled=true", "app.rate-limit.namespace=tenant-1",
                "app.rate-limit.environment=test", "app.rate-limit.hmac-secret=" + SECRET,
                "app.rate-limit.allow-production-bypass=true", "app.rate-limit.trusted-proxies[0]=10.0.0.0/8",
                "app.rate-limit.policies.login-ip.dimension=ip", "app.rate-limit.policies.login-ip.revision=r2",
                "app.rate-limit.policies.login-ip.capacity=15", "app.rate-limit.policies.login-ip.refill-tokens=60",
                "app.rate-limit.policies.login-ip.refill-period=60s").run(context -> {
                    assertThat(context).hasNotFailed();
                    var bound = context.getBean(RateLimitProperties.class);
                    assertThat(bound.namespace()).isEqualTo("tenant-1");
                    assertThat(bound.environment()).isEqualTo("test");
                    assertThat(bound.allowProductionBypass()).isTrue();
                    assertThat(bound.trustedProxies()).containsExactly("10.0.0.0/8");
                    assertThat(context).hasSingleBean(RateLimitKeyFactory.class).hasSingleBean(ClientIpResolver.class);
                    assertThat(bound.policies().get("login-ip").periodMs()).isEqualTo(60000);
                    assertThat(context).hasSingleBean(LimiterRedisConnection.class).hasSingleBean(TokenBucketStore.class);
                    assertThat(context).doesNotHaveBean(io.lettuce.core.RedisClient.class);
                });
    }

    @Test
    void applicationPropertyFilesBindLocalDefaultsAndTestDisable() throws java.io.IOException {
        var mainSource = new ResourcePropertySource("classpath:application.properties");
        var testSource = new ResourcePropertySource("classpath:application-test.properties");
        var mainProperties = runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addLast(mainSource));
        mainProperties.run(context -> {
            assertThat(context).hasNotFailed();
            var bound = context.getBean(RateLimitProperties.class);
            assertThat(bound.enabled()).isTrue();
            assertThat(bound.hmacSecret()).isEqualTo(RateLimitProperties.LOCAL_TEST_SECRET);
            assertThat(bound.trustedProxies()).isEmpty();
            assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy")).isEqualTo("none");
        });
        mainProperties.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(testSource))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RateLimitProperties.class).enabled()).isFalse();
                });
    }

    @Test
    void invalidBoundSecretIsNotExposedInFailure() {
        String invalid = "private-short-secret";
        runner.withPropertyValues("app.rate-limit.hmac-secret=" + invalid).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("32 to 256 UTF-8 bytes")
                    .hasStackTraceContaining("without NUL");
            for (Throwable failure = context.getStartupFailure(); failure != null; failure = failure.getCause()) {
                assertThat(failure.getMessage()).doesNotContain(invalid);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "prod"})
    void profileMatrix(String profile) {
        var profiled = runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile));
        profiled.withPropertyValues("app.rate-limit.environment=" + profile, "app.rate-limit.hmac-secret=" + SECRET)
                .run(context -> assertThat(context).hasNotFailed());
        profiled.withPropertyValues("app.rate-limit.environment=" + profile, "app.rate-limit.enabled=false",
                "app.rate-limit.hmac-secret=").run(context -> {
                    if (profile.equals("prod")) assertThat(context).hasFailed();
                    else assertThat(context).hasNotFailed();
                });
        profiled.withPropertyValues("app.rate-limit.environment=" + profile, "app.rate-limit.enabled=false",
                "app.rate-limit.hmac-secret=", "app.rate-limit.allow-production-bypass=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodProfileCannotUseAnotherEnvironmentEvenWhenDisabled() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("test", "prod"))
                .withPropertyValues("app.rate-limit.enabled=false", "app.rate-limit.allow-production-bypass=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"learne-rate-limit-local-test-key-not-for-production", "local-key-123456789012345678901234567890",
            "test-key-123456789012345678901234567890", "default-key-123456789012345678901234567890",
            "replace-with-123456789012345678901234567890", "a-strong-independent-production-key-123456",
            "development-key-123456789012345678901234567890", "learne-development-key-123456789012345678901234567890",
            "TEST-key-123456789012345678901234567890"})
    void prodRejectsKnownOrJwtSharedSecrets(String secret) {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("app.rate-limit.environment=prod", "app.rate-limit.hmac-secret=" + secret,
                        "app.auth.jwt-secret=" + SECRET).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("Unsafe rate limit configuration");
                    assertThat(rootCause(context.getStartupFailure()).getMessage()).doesNotContain(secret);
                });
    }

    @Test
    void prodAcceptsIndependentGeneratedLookingSecretWithInternalTestSubstring() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("app.rate-limit.environment=prod",
                        "app.rate-limit.hmac-secret=9Xq7V2mK4testR8nP6zW3bY5cH1sF0dA9",
                        "app.auth.jwt-secret=" + SECRET)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodEnvironmentIsProtectedEvenWithoutProdProfile() {
        runner.withPropertyValues("app.rate-limit.environment=prod").run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"namespace=Bad", "namespace=a_b", "namespace=abcdefghijklmnopqrstuvwxyz1234567",
            "environment=abcdefghijklmnopq", "environment=Prod"})
    void rejectsInvalidBoundSchema(String property) {
        validPolicyFixture("login").withPropertyValues("app.rate-limit." + property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid rate limit namespace or environment");
        });
    }

    @Test
    void completePolicyFixtureBindsSuccessfully() {
        validPolicyFixture("login").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RateLimitProperties.class).policies()).containsOnlyKeys("login");
        });
    }

    @Test
    void rejectsInvalidBoundPolicyIdWithOtherwiseValidPolicy() {
        validPolicyFixture("Bad").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid rate limit policy ID");
        });
    }

    @ParameterizedTest
    @CsvSource({
            "dimension=unknown, No enum constant com.learne.backend.ratelimit.RateLimitDimension.unknown",
            "revision=r0, Rate limit dimension and revision must be valid",
            "capacity=0, Rate limit token counts must be between 1 and 1000000",
            "refill-tokens=0, Rate limit token counts must be between 1 and 1000000",
            "refill-period=999ms, Rate limit period must be exact milliseconds between 1000 and 86400000"
    })
    void rejectsSingleInvalidBoundPolicyField(String property, String expectedReason) {
        validPolicyFixture("login").withPropertyValues("app.rate-limit.policies[login]." + property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(expectedReason);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "prod"})
    void validatesProxyCidrsInEveryMode(String mode) {
        for (boolean enabled : List.of(true, false)) {
            var configured = runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(mode))
                    .withPropertyValues("app.rate-limit.environment=" + mode, "app.rate-limit.enabled=" + enabled,
                            "app.rate-limit.allow-production-bypass=true", "app.rate-limit.hmac-secret=" + SECRET);
            configured.withPropertyValues("app.rate-limit.trusted-proxies[0]=private-invalid-cidr")
                    .run(context -> assertThat(context).hasFailed());
            for (String all : List.of("0.0.0.0/0", "::/0")) {
                configured.withPropertyValues("app.rate-limit.trusted-proxies[0]=" + all).run(context -> {
                    if (mode.equals("prod")) assertThat(context).hasFailed();
                    else assertThat(context).hasNotFailed();
                });
            }
        }
    }

    @Test
    void validatorChecksCidrsBeforeDisabledBlankSecretBypassWithoutBeans() {
        var environment = new org.springframework.mock.env.MockEnvironment();
        var configuration = new RateLimitConfiguration();
        for (String mode : List.of("local", "test", "prod")) {
            var invalid = new RateLimitProperties(false, null, mode, "", true, List.of("private-invalid-cidr"), null);
            assertThatIllegalArgumentException().isThrownBy(() -> configuration
                    .rateLimitConfigurationValidator(invalid, environment).afterPropertiesSet())
                    .withMessage("Invalid trusted proxy CIDR");
        }
        for (String cidr : List.of("0.0.0.0/0", "::/0", "::ffff:192.0.2.1/0")) {
            var all = new RateLimitProperties(false, null, "prod", "", true, List.of(cidr), null);
            assertThatIllegalStateException().isThrownBy(() -> configuration
                    .rateLimitConfigurationValidator(all, environment).afterPropertiesSet())
                    .withMessageContaining("trust-all CIDR");
        }
    }

    private ApplicationContextRunner validPolicyFixture(String id) {
        String prefix = "app.rate-limit.policies[" + id + "].";
        return runner.withPropertyValues(prefix + "dimension=ip", prefix + "revision=r1", prefix + "capacity=15",
                prefix + "refill-tokens=60", prefix + "refill-period=60s");
    }

    @Test
    void wiresServiceWithApplicationRegistryWithoutEagerTcpAndIgnoresCacheDisable() {
        var store = org.mockito.Mockito.mock(TokenBucketStore.class);
        org.mockito.Mockito.when(store.consume(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(RateLimitDecision.allowed());
        validPolicyFixture("login").withPropertyValues("app.cache.redis.enabled=false")
                .withBean("testStore", TokenBucketStore.class, () -> store, definition -> definition.setPrimary(true))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RateLimitService.class).hasSingleBean(RateLimitMetrics.class)
                            .hasSingleBean(LimiterRedisConnection.class);
                    assertThat(context.getEnvironment().getProperty("app.cache.redis.enabled", Boolean.class)).isFalse();
                    assertThat(context.getBean(RateLimitProperties.class).enabled()).isTrue();
                    var current = (java.util.concurrent.atomic.AtomicReference<?>) org.springframework.test.util.ReflectionTestUtils
                            .getField(context.getBean(LimiterRedisConnection.class), "current");
                    assertThat(current.get()).isNull();
                    context.getBean(RateLimitService.class).requireAllowed("login", RateLimitDimension.IP, "192.0.2.1");
                    assertThat(current.get()).isNull();
                    assertThat(context.getBean(MeterRegistry.class).get("learne.ratelimit.requests")
                            .tags("policy", "login", "outcome", "allowed").counter().count()).isEqualTo(1);
                });
    }

    @Test
    void disabledRealConfigurationCreatesNoTransportAndRecordsOnlyBypass() {
        runner.withPropertyValues("app.rate-limit.enabled=false", "app.rate-limit.hmac-secret=")
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(LimiterRedisConnection.class)
                            .doesNotHaveBean(TokenBucketStore.class).doesNotHaveBean(io.lettuce.core.RedisClient.class);
                    assertThat(context.getBean(RateLimitService.class).consume("unknown", null, null))
                            .isEqualTo(RateLimitDecision.bypassed());
                    assertThat(context.getBean(MeterRegistry.class).get("learne.ratelimit.requests")
                            .tags("policy", "configuration", "outcome", "bypassed").counter().count()).isEqualTo(1);
                });
    }

    @Test
    void resolverFallbackMetricsHaveOnlyFiniteReasonTagsAndCountOnce() {
        runner.withPropertyValues("app.rate-limit.enabled=false", "app.rate-limit.trusted-proxies[0]=10.0.0.0/8")
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
                    var resolver = context.getBean(ClientIpResolver.class);
                    var registry = context.getBean(MeterRegistry.class);
                    for (var reason : ClientIpResolver.FallbackReason.values()) {
                        var request = new MockHttpServletRequest();
                        request.setRemoteAddr("10.0.0.1");
                        switch (reason) {
                            case MISSING_HEADER -> { }
                            case MALFORMED_HEADER -> request.addHeader("X-Forwarded-For", "private@example.com");
                            case HEADER_TOO_LARGE -> request.addHeader("X-Forwarded-For", "x".repeat(2049));
                            case TOO_MANY_HOPS -> request.addHeader("X-Forwarded-For", String.join(",", java.util.Collections.nCopies(17, "192.0.2.1")));
                            case ALL_TRUSTED -> request.addHeader("X-Forwarded-For", "10.0.0.2");
                        }
                        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
                        assertThat(registry.get("learne.ratelimit.client-ip.fallback")
                                .tag("reason", reason.name().toLowerCase(java.util.Locale.ROOT)).counter().count()).isEqualTo(1);
                    }
                    assertThat(registry.find("learne.ratelimit.client-ip.fallback").counters()).hasSize(5)
                            .allSatisfy(counter -> assertThat(counter.getId().getTags()).hasSize(1));
                });
    }

    @Test
    void closesOnlyOwnedFallbackRegistryAtContextShutdown() {
        var owned = new java.util.concurrent.atomic.AtomicReference<MeterRegistry>();
        runner.withPropertyValues("app.rate-limit.enabled=false").run(context -> {
            owned.set((MeterRegistry) org.springframework.test.util.ReflectionTestUtils
                    .getField(context.getBean(RateLimitMetrics.class), "registry"));
            assertThat(owned.get().isClosed()).isFalse();
        });
        assertThat(owned.get().isClosed()).isTrue();
        var external = new SimpleMeterRegistry();
        try {
            var metrics = new RateLimitMetrics(external, java.util.Set.of());
            metrics.close();
            assertThat(external.isClosed()).isFalse();
        } finally { external.close(); }
    }

    @Test
    void bypassStartupLogIsOnceAndContainsOnlySafeEnvironmentAndOverride() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RateLimitConfiguration.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            runner.withPropertyValues("app.rate-limit.enabled=false", "app.rate-limit.environment=prod",
                    "app.rate-limit.allow-production-bypass=true", "app.rate-limit.hmac-secret=" + SECRET)
                    .run(context -> {
                        var service = context.getBean(RateLimitService.class);
                        service.consume("private@example.com", null, "192.0.2.1");
                        service.requireAllowed(null, null, null);
                    });
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "Rate limiting explicitly bypassed: environment=prod, allow-production-bypass=true");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void validatesRawUtf8ByteLengthWithoutTrimmingOrDecoding() {
        for (String valid : List.of("x".repeat(32), "x".repeat(256), "é".repeat(16), "é".repeat(128), " ".repeat(32))) {
            assertThat(properties(true, null, null, valid, null, null).hmacSecret()).isEqualTo(valid);
        }
        for (String invalid : List.of("", "x".repeat(31), "x".repeat(257), "é".repeat(15), "é".repeat(129), "x".repeat(32) + '\0')) {
            assertThatIllegalArgumentException().isThrownBy(() -> properties(true, null, null, invalid, null, null))
                    .withMessageNotContaining(invalid.isEmpty() ? "secret-value" : invalid);
        }
        assertThatCode(() -> properties(false, null, null, "", null, null)).doesNotThrowAnyException();
    }

    @Test
    void validatesDirectGrammarAndPolicyIds() {
        assertThatCode(() -> properties(true, "a".repeat(32), "a".repeat(16), SECRET, null,
                Map.of("a".repeat(48), new RateLimitPolicy(RateLimitDimension.USER, "r1", 1, 1, Duration.ofSeconds(1)))))
                .doesNotThrowAnyException();
        for (String invalid : List.of("", "A", "a_", "a\n", "a".repeat(49))) {
            assertThatIllegalArgumentException().isThrownBy(() -> properties(true, null, null, SECRET, null,
                    Map.of(invalid, new RateLimitPolicy(RateLimitDimension.USER, "r1", 1, 1, Duration.ofSeconds(1)))));
        }
    }

    private RateLimitProperties properties(Boolean enabled, String namespace, String environment, String secret,
            List<String> proxies, Map<String, RateLimitPolicy> policies) {
        return new RateLimitProperties(enabled, namespace, environment, secret, null, proxies, policies);
    }

    private Throwable rootCause(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}
