package com.learne.backend.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.learne.backend.auth.session.service.RedisLoginAttemptStore;
import com.learne.backend.auth.session.service.JwtService;
import com.learne.backend.auth.session.service.RefreshTokenService;
import com.learne.backend.auth.session.service.LoginAttemptService;
import com.learne.backend.auth.session.dto.LoginRequest;
import com.learne.backend.domain.auth.*;
import com.learne.backend.ratelimit.*;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginProtectionIT extends RedisTestSupport {
    private static final String NAMESPACE = "login-" + UUID.randomUUID().toString().substring(0, 12);
    private static LimiterRedisConnection singletonTransport;
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private UserRepository users;
    @Autowired private RefreshTokenRepository refreshTokens;
    @MockitoSpyBean private PasswordEncoder passwords;
    @MockitoSpyBean private JwtService jwt;
    @MockitoSpyBean private RefreshTokenService refresh;
    @MockitoSpyBean private RedisLoginAttemptStore attempts;
    @Autowired private jakarta.validation.Validator validator;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RateLimitProperties properties;
    @Autowired private RateLimitKeyFactory keys;
    @Autowired private ApplicationContext context;
    @org.springframework.boot.test.web.server.LocalServerPort private int port;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meters;
    private String email;
    private String ip;
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_IP = new java.util.concurrent.atomic.AtomicInteger();

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("app.rate-limit.enabled", () -> true);
        registry.add("app.rate-limit.namespace", () -> NAMESPACE);
        registry.add("app.rate-limit.environment", () -> "test");
        registry.add("spring.data.redis.host", () -> redisUri().getHost());
        registry.add("spring.data.redis.port", () -> redisUri().getPort());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + NAMESPACE
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
    }

    @BeforeEach
    void prepare() {
        singletonTransport = context.getBean(LimiterRedisConnection.class);
        assertThat(context.getBeansOfType(LimiterRedisConnection.class)).hasSize(1);
        assertThat(context.getBean(RedisLoginAttemptStore.class)).isNotNull();
        TestDatabaseCleaner.clean(jdbc);
        // Fixture-only cleanup, between methods; never reset counters inside a login flow.
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            for (String key : commands.keys(NAMESPACE + ":test:*")) commands.del(key);
        }
        email = UUID.randomUUID() + "@example.com";
        ip = "192.0.2." + NEXT_IP.incrementAndGet();
        User user = new User();
        user.setEmail(email);
        user.setFullName("Login Protection");
        user.setRole(UserRole.STUDENT);
        user.setPasswordHash(passwords.encode("Password123"));
        user.setActive(true);
        users.save(user);
    }

    @AfterAll
    static void closeActualSingletonBeforeContainer() throws InterruptedException {
        if (singletonTransport != null) LimiterTestCleanup.closeAndAwait(singletonTransport);
    }

    private ResultActions login(String address, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(request -> { request.setRemoteAddr(ip); return request; })
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(address, password)));
    }

    private String counter() {
        return properties.namespace() + ":test:auth:login-failures:v1:email:"
                + keys.hmacCanonicalIdentifier(RateLimitDimension.EMAIL, email);
    }

    private void assertCounter(String expected) {
        try (var connection = redisClient.connect()) {
            assertThat(connection.sync().get(counter())).isEqualTo(expected);
            if (expected != null) assertThat(connection.sync().pttl(counter())).isBetween(1L, 900000L);
        }
    }

    @Test
    void runtimeChainConsumesExactlyOneIpTokenPerLogin() throws Exception {
        double before = meters.find("learne.ratelimit.requests").tags("policy", "login-ip", "outcome", "allowed")
                .counters().stream().mapToDouble(counter -> counter.count()).sum();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        """
                        {"email":"%s","password":"Password123"}
                        """.formatted(email)))
                .build();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            assertThat(client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
        double after = meters.find("learne.ratelimit.requests").tags("policy", "login-ip", "outcome", "allowed")
                .counters().stream().mapToDouble(counter -> counter.count()).sum();
        assertThat(after - before).isEqualTo(1);
        try (var connection = redisClient.connect()) {
            assertThat(connection.sync().keys(NAMESPACE + ":test:rl:v1:login-ip:*")).hasSize(1);
            assertThat(connection.sync().keys(NAMESPACE + ":test:auth:login-failures:*")).isEmpty();
        }
        assertThat(refreshTokens.findAll()).hasSize(1);
    }

    @Test
    void malformedJsonConsumesOneIpTokenBeforeMvcWithoutEmailFailure() throws Exception {
        double before = meters.find("learne.ratelimit.requests").tags("policy", "login-ip", "outcome", "allowed")
                .counters().stream().mapToDouble(counter -> counter.count()).sum();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{invalid"))
                .andExpect(status().isBadRequest());
        double after = meters.find("learne.ratelimit.requests").tags("policy", "login-ip", "outcome", "allowed")
                .counters().stream().mapToDouble(counter -> counter.count()).sum();
        assertThat(after - before).isEqualTo(1);
        try (var connection = redisClient.connect()) {
            assertThat(connection.sync().keys(NAMESPACE + ":test:rl:v1:login-ip:*")).hasSize(1);
            assertThat(connection.sync().keys(NAMESPACE + ":test:auth:login-failures:*")).isEmpty();
        }
        assertThat(refreshTokens.findAll()).isEmpty();
    }

    @Test
    void loginRejectsAttemptsAfterFiveFailedPasswordsForNormalizedEmail() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            login(email.toUpperCase(Locale.ROOT), "WrongPassword123")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid email or password."));
            assertCounter(Integer.toString(attempt));
        }
        login(email, "Password123")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again later."))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")))
                .andExpect(cookie().doesNotExist("refresh_token"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        assertCounter("5");
        assertThat(refreshTokens.findAll()).isEmpty();
    }

    @Test
    void successfulLoginClearsFailedAttemptCount() throws Exception {
        for (int cycle = 0; cycle < 2; cycle++) {
            for (int attempt = 1; attempt <= 4; attempt++) {
                login(email, "WrongPassword123").andExpect(status().isUnauthorized());
                assertCounter(Integer.toString(attempt));
            }
            login(email, "Password123").andExpect(status().isOk());
            assertCounter(null);
        }
        assertThat(refreshTokens.findAll()).hasSize(2);
    }

    private String ipKey(String address) {
        return keys.key("login-ip", properties.policies().get("login-ip"), RateLimitDimension.IP, address);
    }

    private long redisMillis(io.lettuce.core.api.sync.RedisCommands<String, String> commands) {
        var time = commands.time();
        return Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
    }

    @Test
    void actualServerIpDenialPrecedesValidBearerLookupPasswordAndTokenIssue() throws Exception {
        // Mint a genuinely valid token before clearing spy observations.
        var seeded = login(email, "Password123").andExpect(status().isOk()).andReturn();
        String bearer = json.readTree(seeded.getResponse().getContentAsString()).get("accessToken").asText();
        assertThat(jwt.parseUserId(bearer)).isEqualTo(users.findByEmail(email).orElseThrow().getId());
        long rows = refreshTokens.count();
        String key = ipKey("127.0.0.1");
        try (var connection = redisClient.connect()) {
            var commands = connection.sync();
            long updated = redisMillis(commands) + 30000;
            // Valid bounded future timestamp: production Lua explicitly permits <= now+60s.
            // This prevents a slow CI HTTP roundtrip from refilling the exhausted fixture.
            String state = "{\"schema\":1,\"fingerprint\":\"" + properties.policies().get("login-ip").fingerprint()
                    + "\",\"credit\":0,\"updatedAtMs\":" + updated + "}";
            commands.psetex(key, 45000, state);
            clearInvocations(users, passwords, jwt, refresh, attempts);
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + bearer)
                    .header("X-Forwarded-For", "203.0.113.99")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            "{\"email\":\"" + email + "\",\"password\":\"Password123\"}")).build();
            try (var client = java.net.http.HttpClient.newHttpClient()) {
                var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(redisMillis(commands)).as("fixture remains exhausted without refill").isLessThan(updated);
                assertThat(response.statusCode()).isEqualTo(429);
                assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo("RATE_LIMITED");
                assertThat(response.headers().firstValue("Retry-After")).hasValueSatisfying(value ->
                        assertThat(Long.parseLong(value)).isPositive());
                assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
                assertThat(json.readTree(response.body()).has("accessToken")).isFalse();
            }
            verifyNoInteractions(users, passwords, jwt, refresh, attempts);
            assertThat(commands.get(key)).isEqualTo(state);
            assertThat(commands.exists(ipKey("203.0.113.99"))).isZero();
        }
        assertThat(refreshTokens.count()).isEqualTo(rows);
    }

    @Test
    void successClearsEmailButPreservesIpCreditWithElapsedRefill() throws Exception {
        login(email, "WrongPassword123").andExpect(status().isUnauthorized());
        assertCounter("1");
        try (var connection = redisClient.connect()) {
            String key = ipKey(ip);
            // Leave enough depleted budget that even a 10s CI pause cannot hide a reset.
            String depleted = connection.sync().get(key).replaceFirst("\"credit\":[0-9]+", "\"credit\":180000");
            connection.sync().psetex(key, 15000, depleted);
            var before = json.readTree(connection.sync().get(key));
            login(email, "Password123").andExpect(status().isOk());
            assertCounter(null);
            var after = json.readTree(connection.sync().get(key));
            var policy = properties.policies().get("login-ip");
            long elapsed = after.get("updatedAtMs").asLong() - before.get("updatedAtMs").asLong();
            assertThat(elapsed).isBetween(0L, 10000L);
            long expected = Math.min(policy.capacityCredit(), before.get("credit").asLong()
                    + elapsed * policy.refillTokens()) - policy.periodMs();
            assertThat(after.get("credit").asLong()).isEqualTo(expected);
            assertThat(after.get("fingerprint")).isEqualTo(before.get("fingerprint"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"check", "failure", "clear"})
    void emailDependencyErrorsFailClosedBeforeIssuingAnything(String operation) throws Exception {
        attempts.recordFailure(email);
        switch (operation) {
            case "check" -> doThrow(RateLimitException.unavailable()).when(attempts).assertCanAttempt(email);
            case "failure" -> doThrow(RateLimitException.unavailable()).when(attempts).recordFailure(email);
            case "clear" -> doThrow(RateLimitException.unavailable()).when(attempts).clear(email);
            default -> throw new AssertionError(operation);
        }
        clearInvocations(attempts, users, passwords, jwt, refresh);
        login(email, operation.equals("failure") ? "WrongPassword123" : "Password123")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ABUSE_PROTECTION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Service temporarily unavailable. Please try again later."))
                .andExpect(cookie().doesNotExist("refresh_token"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        var order = inOrder(attempts, users, passwords);
        order.verify(attempts).assertCanAttempt(email);
        if (operation.equals("check")) {
            verifyNoInteractions(users, passwords);
        } else {
            order.verify(users).findByEmail(email);
            order.verify(passwords).matches(any(), any());
            if (operation.equals("failure")) order.verify(attempts).recordFailure(email);
            else order.verify(attempts).clear(email);
        }
        verifyNoMoreInteractions(attempts);
        verifyNoInteractions(jwt, refresh);
        assertCounter("1");
        assertThat(refreshTokens.findAll()).isEmpty();
    }

    @Test
    void dtoValidUnicodeBeyond254Utf8BytesAuthenticatesNormally() throws Exception {
        String unicode = "é".repeat(32) + "@" + ("é".repeat(30) + ".").repeat(3) + "é".repeat(30);
        assertThat(unicode.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(254);
        assertThat(unicode.length()).isLessThanOrEqualTo(254);
        assertThat(validator.validate(new LoginRequest(unicode, "Password123"))).isEmpty();
        var user = users.findByEmail(email).orElseThrow();
        user.setEmail(unicode);
        users.saveAndFlush(user);
        email = unicode;
        login(unicode, "WrongPassword123").andExpect(status().isUnauthorized());
        assertCounter("1");
        login(unicode, "Password123").andExpect(status().isOk());
        assertCounter(null);
        assertThat(refreshTokens.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "bad\u0000@example.com"})
    void invalidDtoRemainsValidationErrorWithoutEmailMutation(String invalid) throws Exception {
        var dto = new LoginRequest(invalid, "Password123");
        assertThat(validator.validate(dto)).isNotEmpty();
        clearInvocations(attempts, users, passwords, jwt, refresh);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(r -> { r.setRemoteAddr(ip); return r; }).content(json.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(attempts, users, passwords, jwt, refresh);
        assertThat(refreshTokens.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "locked"})
    void accountStatusIsCheckedOnlyAfterPasswordAndRetainsFailures(String state) throws Exception {
        var user = users.findByEmail(email).orElseThrow();
        user.setActive(!state.equals("inactive"));
        user.setLocked(state.equals("locked"));
        users.saveAndFlush(user);
        login(email, "WrongPassword123").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
        assertCounter("1");
        clearInvocations(attempts, jwt, refresh);
        login(email, "Password123").andExpect(status().isForbidden())
                .andExpect(cookie().doesNotExist("refresh_token"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        verify(attempts).assertCanAttempt(email);
        verifyNoMoreInteractions(attempts);
        verifyNoInteractions(jwt, refresh);
        assertCounter("1");
        assertThat(refreshTokens.count()).isZero();
    }

    @Test
    void unknownEmailCaseVariantsUseSameGenericCooldown() throws Exception {
        email = "unknown-" + email;
        for (int i = 1; i <= 5; i++) {
            login(i % 2 == 0 ? email : email.toUpperCase(Locale.ROOT), "WrongPassword123")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid email or password."));
            assertCounter(Integer.toString(i));
        }
        login(email.toUpperCase(Locale.ROOT), "Password123").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again later."))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")))
                .andExpect(cookie().doesNotExist("refresh_token"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        assertCounter("5");
        assertThat(refreshTokens.count()).isZero();
    }

    @Test
    void admittedSuccessMayClearAnotherInstancesLockThenLateFailureStartsAtOne() {
        var firstStore = new RedisLoginAttemptStore(properties, keys, singletonTransport);
        var secondStore = new RedisLoginAttemptStore(properties, keys, singletonTransport);
        var firstBeans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        firstBeans.addBean("store", firstStore);
        var secondBeans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        secondBeans.addBean("store", secondStore);
        var a = new LoginAttemptService(properties, firstBeans.getBeanProvider(RedisLoginAttemptStore.class));
        var b = new LoginAttemptService(properties, secondBeans.getBeanProvider(RedisLoginAttemptStore.class));
        a.assertCanAttempt(email); // A admitted; its credential work is in flight.
        for (int i = 0; i < 5; i++) b.recordFailure(email);
        assertCounter("5");
        assertThatThrownBy(() -> b.assertCanAttempt(email)).isInstanceOf(RateLimitException.class);
        a.clear(email); // Already-admitted valid A completes, as specified (no global reservation).
        assertCounter(null);
        b.recordFailure(email); // A late failed credential result starts a fresh window.
        assertCounter("1");
        a.assertCanAttempt(email);
    }

    @Test
    void refreshLogoutAndOptionsDoNotConsumeExistingLoginIpKey() throws Exception {
        login(email, "WrongPassword123").andExpect(status().isUnauthorized());
        try (var connection = redisClient.connect()) {
            String key = ipKey(ip);
            String before = connection.sync().get(key);
            long ttl = connection.sync().pttl(key);
            mvc.perform(post("/api/auth/refresh").with(r -> { r.setRemoteAddr(ip); return r; }))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/auth/logout").with(r -> { r.setRemoteAddr(ip); return r; }))
                    .andExpect(status().isOk());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/auth/login")
                    .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk());
            assertThat(connection.sync().get(key)).isEqualTo(before);
            assertThat(connection.sync().pttl(key)).isBetween(1L, ttl);
        }
        assertCounter("1");
    }
}
