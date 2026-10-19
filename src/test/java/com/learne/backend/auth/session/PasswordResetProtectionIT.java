package com.learne.backend.auth.session;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.learne.backend.auth.session.service.*;
import com.learne.backend.domain.auth.*;
import com.learne.backend.ratelimit.*;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetProtectionIT extends RedisTestSupport {
    private static final String NAMESPACE = "reset-" + UUID.randomUUID().toString().substring(0, 12);
    private static final AtomicInteger NEXT_IP = new AtomicInteger();
    private static LimiterRedisConnection transport;
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private UserRepository users;
    @MockitoSpyBean private PasswordResetTokenRepository tokens;
    @Autowired private RefreshTokenRepository sessions;
    @MockitoSpyBean private RefreshTokenService refresh;
    @MockitoBean private PasswordResetMailService mail;
    @MockitoBean private PasswordResetTokenGenerator generator;
    @MockitoSpyBean private PasswordEncoder encoder;
    @MockitoSpyBean private JwtService jwt;
    @MockitoSpyBean private TokenBucketStore store;
    @Autowired private RateLimitProperties policies;
    @Autowired private RateLimitKeyFactory keys;
    @Autowired private RateLimitMetrics metrics;
    @Autowired private tools.jackson.databind.ObjectMapper json;
    @Autowired private jakarta.validation.Validator validator;
    @org.springframework.boot.test.web.server.LocalServerPort private int port;
    private static final String REQUEST = "password-reset-request-ip", EMAIL = "password-reset-request-email",
            CONFIRM = "password-reset-confirm-ip";
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext context;
    @Autowired private PlatformTransactionManager transactions;
    private User user;
    private String ip;
    private Long oldId;
    private Long latestId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.rate-limit.enabled", () -> true);
        registry.add("app.rate-limit.namespace", () -> NAMESPACE);
        registry.add("app.rate-limit.environment", () -> "test");
        registry.add("spring.data.redis.host", () -> redisUri().getHost());
        registry.add("spring.data.redis.port", () -> redisUri().getPort());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + NAMESPACE
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;LOCK_TIMEOUT=5000");
    }

    @BeforeEach
    void prepare() throws Exception {
        transport = context.getBean(LimiterRedisConnection.class);
        assertThat(context.getBeansOfType(LimiterRedisConnection.class)).hasSize(1);
        TestDatabaseCleaner.clean(jdbc);
        // Only this isolated fixture namespace, between methods; never refund within a flow.
        try (var c = redisClient.connect()) {
            for (String key : c.sync().keys(NAMESPACE + ":test:*")) c.sync().del(key);
        }
        ip = "192.0.2." + NEXT_IP.incrementAndGet();
        user = saveUser();
        oldId = seedToken(user, "135790", OffsetDateTime.now().minusMinutes(2), null).getId();
        when(generator.generate()).thenReturn("246801", "357912");
        mvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .with(r -> { r.setRemoteAddr(ip); return r; })
                .content("{\"email\":\"" + user.getEmail() + "\"}"))
                .andExpect(status().isOk());
        verify(mail).sendPasswordResetToken(argThat(t -> t.getUser().getEmail().equals(user.getEmail())), eq("246801"));
        latestId = tokens.findTopByUserAndUsedAtIsNullOrderByCreatedAtDesc(user).orElseThrow().getId();
        for (int i = 0; i < 2; i++) {
            var session = new RefreshToken();
            session.setUser(user);
            session.setTokenHash(UUID.randomUUID().toString());
            session.setCreatedAt(OffsetDateTime.now());
            session.setExpiresAt(OffsetDateTime.now().plusDays(1));
            sessions.saveAndFlush(session);
        }
    }

    @AfterAll
    static void closeTransport() throws InterruptedException {
        if (transport != null) LimiterTestCleanup.closeAndAwait(transport);
    }

    private User saveUser() {
        var value = new User();
        value.setEmail(UUID.randomUUID() + "@example.com");
        value.setFullName("Reset Protection");
        value.setPasswordHash(encoder.encode("Password123"));
        value.setRole(UserRole.TEACHER);
        value.setActive(true);
        value.setMustChangePassword(true);
        return users.saveAndFlush(value);
    }

    private PasswordResetToken seedToken(User owner, String code, OffsetDateTime created, OffsetDateTime used) {
        var token = new PasswordResetToken();
        token.setUser(owner);
        token.setTokenHash(encoder.encode(code));
        token.setCreatedAt(created);
        token.setExpiresAt(OffsetDateTime.now().plusMinutes(15));
        token.setUsedAt(used);
        return tokens.saveAndFlush(token);
    }

    private ResultActions confirm(String code, String password, String confirmation) throws Exception {
        return mvc.perform(post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .with(r -> { r.setRemoteAddr(ip); return r; })
                .content("""
                        {"email":"%s","token":"%s","newPassword":"%s","confirmNewPassword":"%s"}
                        """.formatted(user.getEmail(), code, password, confirmation)));
    }

    // JDBC reads run after the request transaction ends: no persistence-context/mock oracle.
    private List<Map<String, Object>> snapshot() {
        var result = new ArrayList<Map<String, Object>>();
        result.addAll(jdbc.queryForList("select id, password_hash, must_change_password, is_active, is_locked, role from users order by id"));
        result.addAll(jdbc.queryForList("select id, token_hash, attempts, used_at from password_reset_tokens order by id"));
        result.addAll(jdbc.queryForList("select id, revoked, revoked_at, replaced_by_token_id from refresh_tokens order by id"));
        return result;
    }

    private String key(String policy, String identity) {
        return keys.key(policy, policies.policies().get(policy), policy.equals(EMAIL)
                ? RateLimitDimension.EMAIL : RateLimitDimension.IP, identity);
    }

    private String raw(String policy, String identity) {
        try (var c = redisClient.connect()) { return c.sync().get(key(policy, identity)); }
    }

    private long redisMillis() {
        try (var c = redisClient.connect()) {
            var time = c.sync().time();
            return Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
        }
    }

    private void put(String policy, String identity, String state) {
        try (var c = redisClient.connect()) { c.sync().psetex(key(policy, identity), 120000, state); }
    }

    private long exhaust(String policy, String identity) {
        long future = redisMillis() + 30000;
        put(policy, identity, "{\"schema\":1,\"fingerprint\":\"" + policies.policies().get(policy).fingerprint()
                + "\",\"credit\":0,\"updatedAtMs\":" + future + "}");
        return future;
    }

    private void clearObservations() { clearInvocations(users, tokens, encoder, jwt, refresh, generator, mail, store); }

    private ResultActions request(String email) throws Exception {
        return mvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .with(r -> { r.setRemoteAddr(ip); return r; })
                .content(json.writeValueAsString(Map.of("email", email))));
    }

    private String confirmBody(String email) throws Exception {
        return json.writeValueAsString(Map.of("email", email, "token", "246801",
                "newPassword", "NewPassword123", "confirmNewPassword", "NewPassword123"));
    }

    private java.net.http.HttpResponse<String> http(String route, String body, String bearer) throws Exception {
        var builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                "http://127.0.0.1:" + port + "/api/auth/password-reset/" + route))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer)
                .header("X-Forwarded-For", "203.0.113.99");
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            return client.send(builder.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertSpent(String policy, String identity) throws Exception {
        assertThat(json.readTree(raw(policy, identity)).get("credit").asLong())
                .isEqualTo(policies.policies().get(policy).capacityCredit() - policies.policies().get(policy).periodMs());
    }

    private void assertDebit(String policy, String before, String after) throws Exception {
        var b = json.readTree(before); var a = json.readTree(after); var p = policies.policies().get(policy);
        long elapsed = a.get("updatedAtMs").asLong() - b.get("updatedAtMs").asLong();
        assertThat(elapsed).isBetween(0L, 10000L);
        assertThat(a.get("credit").asLong()).isEqualTo(Math.min(p.capacityCredit(),
                b.get("credit").asLong() + elapsed * p.refillTokens()) - p.periodMs());
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "confirm"})
    void realServerIpDenialPrecedesValidBearerAndEveryCredentialEffect(String route) throws Exception {
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(r -> { r.setRemoteAddr(ip); return r; })
                .content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", "Password123"))))
                .andExpect(status().isOk()).andReturn();
        String bearer = json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        assertThat(jwt.parseUserId(bearer)).isEqualTo(user.getId());
        String policy = route.equals("request") ? REQUEST : CONFIRM;
        long future = exhaust(policy, "127.0.0.1");
        String state = raw(policy, "127.0.0.1");
        var before = snapshot();
        clearObservations();
        var response = http(route, route.equals("request") ? json.writeValueAsString(Map.of("email", user.getEmail()))
                : confirmBody(user.getEmail()), bearer);
        assertThat(redisMillis()).isLessThan(future);
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(json.readTree(response.body()).get("message").asText()).isEqualTo("Too many requests. Please try again later.");
        assertThat(response.headers().firstValue("Retry-After")).hasValueSatisfying(v -> assertThat(Long.parseLong(v)).isPositive());
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(json.readTree(response.body()).has("accessToken")).isFalse();
        verifyNoInteractions(users, tokens, encoder, jwt, refresh, generator, mail);
        assertThat(raw(policy, "127.0.0.1")).isEqualTo(state);
        assertThat(raw(policy, "203.0.113.99")).isNull();
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "confirm"})
    void actualInvalidDtoConsumesExactlyOneIpAndNoCredentialWork(String route) throws Exception {
        var before = snapshot();
        clearObservations();
        assertThat(http(route, "{}", null).statusCode()).isEqualTo(400);
        assertSpent(route.equals("request") ? REQUEST : CONFIRM, "127.0.0.1");
        verifyNoInteractions(users, tokens, encoder, jwt, refresh, generator, mail);
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"known", "unknown", "inactive", "locked"})
    void allAccountStatesSpendEmailBeforeLookupAndShareGenericResponses(String state) throws Exception {
        var owner = saveUser();
        owner.setActive(!state.equals("inactive")); owner.setLocked(state.equals("locked"));
        users.saveAndFlush(owner);
        String address = state.equals("unknown") ? "unknown-" + owner.getEmail() : owner.getEmail();
        // Observe the real Lua result before returning to the service's lookup step.
        doAnswer(call -> {
            var decision = new RedisTokenBucketStore(transport).consume(call.getArgument(0), call.getArgument(1));
            assertSpent(EMAIL, address);
            verify(users, never()).findByEmail(address);
            return decision;
        }).when(store).consume(eq(key(EMAIL, address)), any());
        long count = tokens.count();
        clearObservations();
        request(address.toUpperCase(Locale.ROOT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(PasswordResetService.REQUEST_MESSAGE));
        assertSpent(EMAIL, address);
        verify(users).findByEmail(address);
        if (state.equals("known")) {
            verify(mail).sendPasswordResetToken(argThat(t -> t.getUser().getEmail().equals(address)), eq("357912"));
            assertThat(tokens.count()).isEqualTo(count + 1);
        } else {
            verifyNoInteractions(generator, mail, encoder);
            assertThat(tokens.count()).isEqualTo(count);
        }
        String emailState = raw(EMAIL, address);
        assertThat(key(EMAIL, address)).doesNotContain(address).endsWith(keys.hmacCanonicalIdentifier(RateLimitDimension.EMAIL, address));
        var before = snapshot();
        ip = "198.51.100." + NEXT_IP.incrementAndGet();
        clearObservations();
        request(address).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again later."))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")));
        verifyNoInteractions(users, tokens, encoder, generator, mail);
        assertThat(raw(EMAIL, address)).isEqualTo(emailState);
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "confirm"})
    void malformedJsonSpendsIpOnlyAndPreservesLegacyErrorDispatch(String route) throws Exception {
        var before = snapshot();
        clearObservations();
        // Real servlet ERROR dispatch to the existing authenticated /error route yields 401.
        assertThat(http(route, "{invalid", null).statusCode()).isEqualTo(401);
        String policy = route.equals("request") ? REQUEST : CONFIRM;
        assertSpent(policy, "127.0.0.1");
        mvc.perform(post("/api/auth/password-reset/" + route).contentType(MediaType.APPLICATION_JSON)
                .with(r -> { r.setRemoteAddr("203.0.113.50"); return r; }).content("{invalid"))
                .andExpect(status().isBadRequest());
        assertSpent(policy, "203.0.113.50");
        verifyNoInteractions(users, tokens, encoder, generator, mail, refresh);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void enqueueFailureRollsBackSqlButNeverRefundsEmailAllowance() throws Exception {
        var owner = saveUser();
        seedToken(owner, "468023", OffsetDateTime.now().minusMinutes(1), null);
        var before = snapshot();
        var failure = new IllegalStateException("Injected queue failure");
        doAnswer(call -> {
            tokens.flush();
            assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens where user_id = ?", Long.class, owner.getId())).isEqualTo(2L);
            assertSpent(EMAIL, owner.getEmail());
            throw failure;
        }).when(mail).sendPasswordResetToken(argThat(t -> t.getUser().getEmail().equals(owner.getEmail())), anyString());
        clearObservations();
        assertThatThrownBy(() -> request(owner.getEmail())).isInstanceOf(jakarta.servlet.ServletException.class).hasRootCause(failure);
        verify(mail).sendPasswordResetToken(argThat(t -> t.getUser().getEmail().equals(owner.getEmail())), eq("357912"));
        assertThat(snapshot()).isEqualTo(before);
        assertSpent(EMAIL, owner.getEmail());
        String spent = raw(EMAIL, owner.getEmail());
        clearObservations();
        request(owner.getEmail()).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verifyNoInteractions(users, tokens, generator, mail, encoder);
        assertThat(raw(EMAIL, owner.getEmail())).isEqualTo(spent);
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"request,ip,corrupt", "confirm,ip,corrupt", "request,email,corrupt",
            "request,ip,unavailable", "confirm,ip,unavailable", "request,email,unavailable"})
    void dependencyFailuresAre503BeforeLookupOrMutation(String route, String dimension, String fault) throws Exception {
        String policy = dimension.equals("email") ? EMAIL : route.equals("request") ? REQUEST : CONFIRM;
        String identity = dimension.equals("email") ? user.getEmail() : ip;
        if (fault.equals("corrupt")) put(policy, identity, "corrupt-state");
        else doThrow(new IllegalStateException("Injected dependency outage")).when(store)
                .consume(eq(key(policy, identity)), any());
        String beforeIp = raw(REQUEST, ip);
        var before = snapshot();
        clearObservations();
        var result = route.equals("request") ? request(user.getEmail()) : confirm("246801", "NewPassword123", "NewPassword123");
        result.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ABUSE_PROTECTION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Service temporarily unavailable. Please try again later."))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(users, tokens, encoder, generator, mail, refresh);
        assertThat(snapshot()).isEqualTo(before);
        if (dimension.equals("email")) assertDebit(REQUEST, beforeIp, raw(REQUEST, ip));
        if (fault.equals("corrupt")) assertThat(raw(policy, identity)).isEqualTo("corrupt-state");
    }

    @Test
    void successPreservesSeparateIpAndEmailBudgetsAndUnrelatedRoutesLeaveThemUntouched() throws Exception {
        String requestIp = raw(REQUEST, ip), email = raw(EMAIL, user.getEmail());
        assertSpent(REQUEST, ip); assertSpent(EMAIL, user.getEmail());
        confirm("246801", "NewPassword123", "NewPassword123").andExpect(status().isOk());
        assertSpent(CONFIRM, ip);
        assertThat(raw(REQUEST, ip)).isEqualTo(requestIp);
        assertThat(raw(EMAIL, user.getEmail())).isEqualTo(email);
        String confirmIp = raw(CONFIRM, ip);
        for (String route : List.of("/api/auth/login", "/api/auth/register/request-otp", "/api/auth/refresh", "/api/auth/logout")) {
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).with(r -> { r.setRemoteAddr(ip); return r; }).content("{}"));
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/auth/password-reset/request")
                .with(r -> { r.setRemoteAddr(ip); return r; }));
        assertThat(raw(REQUEST, ip)).isEqualTo(requestIp);
        assertThat(raw(EMAIL, user.getEmail())).isEqualTo(email);
        assertThat(raw(CONFIRM, ip)).isEqualTo(confirmIp);
        assertThat(Set.of(key(REQUEST, ip), key(CONFIRM, ip), key("login-ip", ip), key("register-send-ip", ip))).hasSize(4);
    }

    @Test
    void twoServiceInstancesShareAtomicEmailAllowanceOnSingletonTransport() throws Exception {
        String address = "shared-" + UUID.randomUUID() + "@example.com";
        var first = new RateLimitService(policies, keys, () -> new RedisTokenBucketStore(transport), metrics);
        var second = new RateLimitService(policies, keys, () -> new RedisTokenBucketStore(transport), metrics);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); return first.consume(EMAIL, RateLimitDimension.EMAIL, address).outcome(); });
            var b = pool.submit(() -> { start.await(); return second.consume(EMAIL, RateLimitDimension.EMAIL, address.toUpperCase(Locale.ROOT)).outcome(); });
            start.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(RateLimitDecision.Outcome.ALLOWED, RateLimitDecision.Outcome.REJECTED);
            assertSpent(EMAIL, address);
        } finally {
            start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void dtoValidUnicodeAndRootNormalizationWorkWithoutByteCapOrConfirmEmailBudget() throws Exception {
        String address = "é".repeat(32) + "@" + ("é".repeat(30) + ".").repeat(3) + "é".repeat(30);
        assertThat(address.length()).isLessThanOrEqualTo(255);
        assertThat(address.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(254);
        assertThat(validator.validate(new com.learne.backend.auth.session.dto.PasswordResetRequest(address))).isEmpty();
        var owner = saveUser(); owner.setEmail(address); users.saveAndFlush(owner);
        assertThat(http("request", json.writeValueAsString(Map.of("email", address)), null).statusCode()).isEqualTo(200);
        assertSpent(EMAIL, address);
        assertSpent(REQUEST, "127.0.0.1");
        verify(mail).sendPasswordResetToken(argThat(t -> t.getUser().getEmail().equals(address)), eq("357912"));
        String spent = raw(EMAIL, address), requestIp = raw(REQUEST, "127.0.0.1");
        assertThat(http("confirm", confirmBody(address).replace("246801", "357912"), null).statusCode()).isEqualTo(200);
        assertSpent(CONFIRM, "127.0.0.1");
        assertThat(raw(EMAIL, address)).isEqualTo(spent);
        assertThat(raw(REQUEST, "127.0.0.1")).isEqualTo(requestIp);
        assertThat(encoder.matches("NewPassword123", users.findById(owner.getId()).orElseThrow().getPasswordHash())).isTrue();
        String root = "I-" + UUID.randomUUID() + "@example.com";
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            request(root).andExpect(status().isOk());
            verify(users).findByEmail(root.toLowerCase(Locale.ROOT));
            assertSpent(EMAIL, root.toLowerCase(Locale.ROOT));
        } finally { Locale.setDefault(previous); }
        var before = snapshot();
        request("invalid-email").andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
        assertThat(raw(EMAIL, "invalid-email")).isNull();
    }

    @Test
    void successInvalidatesAllCommittedGenerationsAndCleanupCannotResurrectOldCode() throws Exception {
        var other = seedToken(saveUser(), "468023", OffsetDateTime.now(), null);
        var alreadyUsed = seedToken(user, "579134", OffsetDateTime.now().minusMinutes(3), OffsetDateTime.now().minusMinutes(1));
        var usedBefore = tokens.findById(alreadyUsed.getId()).orElseThrow().getUsedAt();
        confirm("246801", "NewPassword123", "NewPassword123").andExpect(status().isOk());
        assertThat(tokens.findById(oldId).orElseThrow().getUsedAt()).isNotNull();
        assertThat(tokens.findById(oldId).orElseThrow().getUsedAt()).isEqualTo(tokens.findById(latestId).orElseThrow().getUsedAt());
        assertThat(tokens.findById(other.getId()).orElseThrow().getUsedAt()).isNull();
        assertThat(tokens.findById(alreadyUsed.getId()).orElseThrow().getUsedAt()).isEqualTo(usedBefore);
        assertThat(encoder.matches("NewPassword123", users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
        assertThat(users.findById(user.getId()).orElseThrow().isMustChangePassword()).isFalse();
        assertThat(sessions.findAllByUserAndRevokedFalse(user)).isEmpty();
        var after = snapshot();
        for (String code : List.of("135790", "246801")) {
            confirm(code, "AnotherPassword123", "AnotherPassword123").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Password reset token is invalid."));
            assertThat(snapshot()).isEqualTo(after);
        }
        new TransactionTemplate(transactions).executeWithoutResult(s -> tokens.deleteByExpiresAtBeforeOrUsedAtIsNotNull(OffsetDateTime.now()));
        var cleaned = snapshot();
        for (String code : List.of("135790", "246801")) {
            confirm(code, "AnotherPassword123", "AnotherPassword123").andExpect(status().isBadRequest());
            assertThat(snapshot()).isEqualTo(cleaned);
        }
    }

    @ParameterizedTest
    @CsvSource({"NewPassword123,Different123", "password,password", "Password123,Password123"})
    void invalidNewPasswordLeavesAllCredentialsAndTokensUntouched(String password, String confirmation) throws Exception {
        var before = snapshot();
        confirm("246801", password, confirmation).andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void fifthWrongCodeCommitsAttemptFiveAndExhaustionDoesNotMutateAgain() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            confirm("000000", "NewPassword123", "NewPassword123")
                    .andExpect(status().is(attempt == 5 ? 429 : 400));
            assertThat(tokens.findById(latestId).orElseThrow().getAttempts()).isEqualTo(attempt);
        }
        var before = snapshot();
        confirm("246801", "NewPassword123", "NewPassword123").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many invalid OTP attempts. Please request a new code."));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(tokens.findById(oldId).orElseThrow().getUsedAt()).isNull();
    }

    @Test
    void simultaneousConfirmsHaveOneSuccessAndOneBusinessRejection() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call = () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                var response = confirm("246801", "NewPassword123", "NewPassword123").andReturn().getResponse();
                if (response.getStatus() != 200) assertThat(response.getContentAsString()).contains("Password reset token is invalid.");
                return response.getStatus();
            };
            var first = pool.submit(call);
            var second = pool.submit(call);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 400);
            assertThat(tokens.findById(oldId).orElseThrow().getUsedAt()).isNotNull();
            assertThat(sessions.findAllByUserAndRevokedFalse(user)).isEmpty();
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"STUDENT,true,false", "TEACHER,false,true", "ADMIN,false,false"})
    void confirmPreservesRoleAndAccountStatus(String role, boolean active, boolean locked) throws Exception {
        user.setRole(UserRole.valueOf(role));
        user.setActive(active);
        user.setLocked(locked);
        users.saveAndFlush(user);
        confirm("246801", "NewPassword123", "NewPassword123").andExpect(status().isOk());
        var after = users.findById(user.getId()).orElseThrow();
        assertThat(after.getRole()).isEqualTo(UserRole.valueOf(role));
        assertThat(after.isActive()).isEqualTo(active);
        assertThat(after.isLocked()).isEqualTo(locked);
    }

    @ParameterizedTest
    @CsvSource({"consume,api", "revoke,api", "consume,lock", "revoke,lock", "consume,runtime", "revoke,runtime"})
    void failureAfterFlushedPartialMutationRollsBackEverySqlRecord(String stage, String kind) throws Exception {
        RuntimeException failure = switch (kind) {
            case "api" -> new ApiException(HttpStatus.BAD_REQUEST, "Injected mutation failure.");
            case "lock" -> new CannotAcquireLockException("Injected cleanup lock abort");
            default -> new IllegalStateException("Injected runtime failure");
        };
        var before = snapshot();
        org.mockito.stubbing.Answer<Object> abort = invocation -> {
            if (stage.equals("consume") && ((PasswordResetToken) invocation.getArgument(0)).getUsedAt() == null)
                return invocation.callRealMethod();
            users.flush();
            tokens.flush();
            // A real partial session write in the SAME application transaction, before the fault.
            jdbc.update("update refresh_tokens set revoked = true, revoked_at = current_timestamp where user_id = ?", user.getId());
            assertThat(jdbc.queryForObject("select must_change_password from users where id = ?", Boolean.class, user.getId())).isFalse();
            assertThat(jdbc.queryForObject("select used_at is not null from password_reset_tokens where id = ?", Boolean.class, latestId)).isTrue();
            throw failure;
        };
        if (stage.equals("consume")) doAnswer(abort).when(tokens).save(any(PasswordResetToken.class));
        else doAnswer(abort).when(refresh).revokeAllActiveTokens(any(User.class));
        if (kind.equals("api")) {
            confirm("246801", "NewPassword123", "NewPassword123").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Injected mutation failure."));
        } else {
            assertThatThrownBy(() -> confirm("246801", "NewPassword123", "NewPassword123"))
                    .isInstanceOf(jakarta.servlet.ServletException.class).hasRootCause(failure);
        }
        assertThat(snapshot()).isEqualTo(before);
    }
}
