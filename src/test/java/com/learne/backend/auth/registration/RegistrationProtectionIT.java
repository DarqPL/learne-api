package com.learne.backend.auth.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.learne.backend.auth.registration.dto.RequestRegistrationOtpRequest;
import com.learne.backend.auth.registration.service.*;
import com.learne.backend.auth.session.service.JwtService;
import com.learne.backend.domain.auth.*;
import com.learne.backend.ratelimit.*;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegistrationProtectionIT extends RedisTestSupport {
    private static final String NAMESPACE = "registration-" + UUID.randomUUID().toString().substring(0, 12);
    private static final String SEND = "register-send-ip", EMAIL = "register-send-email", VERIFY = "register-verify-ip";
    private static final AtomicInteger NEXT_IP = new AtomicInteger();
    private static LimiterRedisConnection singletonTransport;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext context;
    @Autowired private RateLimitProperties properties;
    @Autowired private RateLimitKeyFactory keys;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired private jakarta.validation.Validator validator;
    @MockitoSpyBean private UserRepository users;
    @MockitoSpyBean private RegistrationOtpRepository pending;
    @MockitoSpyBean private PasswordEncoder passwords;
    @MockitoSpyBean private OtpGenerator generator;
    @MockitoSpyBean private JwtService jwt;
    @MockitoSpyBean private RateLimitService limiter;
    // Replace the mail boundary entirely: no SMTP connection can be opened by this fixture.
    @MockitoBean private RegistrationMailService mail;
    @org.springframework.boot.test.web.server.LocalServerPort private int port;
    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private final AtomicInteger mailAttempts = new AtomicInteger();
    private String email, ip;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
        TestDatabaseCleaner.clean(jdbc);
        try (var c = redisClient.connect()) {
            for (String key : c.sync().keys(NAMESPACE + ":test:*")) c.sync().del(key);
        }
        email = UUID.randomUUID() + "@example.com";
        ip = "192.0.2." + NEXT_IP.incrementAndGet();
        delivered.clear();
        mailAttempts.set(0);
        doAnswer(call -> {
            mailAttempts.incrementAndGet();
            delivered.add(call.getArgument(1));
            return null;
        }).when(mail).sendRegistrationOtp(anyString(), anyString());
        clearObservations();
    }

    @AfterAll
    static void closeActualSingletonBeforeContainer() throws InterruptedException {
        if (singletonTransport != null) LimiterTestCleanup.closeAndAwait(singletonTransport);
    }

    private void clearObservations() {
        clearInvocations(users, pending, passwords, generator, jwt, limiter, mail);
    }

    private String requestBody(String address) throws Exception {
        return json.writeValueAsString(new RequestRegistrationOtpRequest("Registration Test", address, "Password123", "Password123"));
    }

    private ResultActions postRoute(String route, String body) throws Exception {
        return mvc.perform(post("/api/auth/register/" + route).contentType(MediaType.APPLICATION_JSON)
                .with(r -> { r.setRemoteAddr(ip); return r; }).content(body));
    }

    private ResultActions request(String address) throws Exception { return postRoute("request-otp", requestBody(address)); }
    private ResultActions resend(String address) throws Exception {
        return postRoute("resend-otp", json.writeValueAsString(Map.of("email", address)));
    }
    private ResultActions verifyOtp(String otp) throws Exception {
        return postRoute("verify-otp", json.writeValueAsString(Map.of("email", email, "otp", otp)));
    }
    private String key(String policy, String identity) {
        return keys.key(policy, properties.policies().get(policy), policy.equals(EMAIL) ? RateLimitDimension.EMAIL : RateLimitDimension.IP, identity);
    }
    private String raw(String key) {
        try (var c = redisClient.connect()) { return c.sync().get(key); }
    }
    private long redisMillis() {
        try (var c = redisClient.connect()) {
            var time = c.sync().time();
            return Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
        }
    }
    private String state(String policy, long credit, long updated) {
        return "{\"schema\":1,\"fingerprint\":\"" + properties.policies().get(policy).fingerprint()
                + "\",\"credit\":" + credit + ",\"updatedAtMs\":" + updated + "}";
    }
    private void put(String key, String value) {
        try (var c = redisClient.connect()) { c.sync().psetex(key, 120000, value); }
    }
    private void exhausted(String policy, String identity) { put(key(policy, identity), state(policy, 0, redisMillis() + 30000)); }
    private RegistrationOtp row() { return pending.findByEmail(email).orElseThrow(); }
    private List<Object> snapshot() {
        var p = row();
        return List.of(p.getId(), p.getEmail(), p.getFullName(), p.getPasswordHash(), p.getOtpHash(),
                p.getExpiresAt(), p.getAttempts(), p.getCreatedAt(), p.getUpdatedAt());
    }
    private void expire() { var p = row(); p.setExpiresAt(OffsetDateTime.now().minusSeconds(1)); pending.saveAndFlush(p); }
    private void seedExpired() {
        var p = new RegistrationOtp();
        p.setEmail(email); p.setFullName("Seed"); p.setPasswordHash(passwords.encode("Password123"));
        p.setOtpHash(passwords.encode("123456")); p.setExpiresAt(OffsetDateTime.now().minusSeconds(1)); p.setAttempts(3);
        pending.saveAndFlush(p);
    }
    private void seedActive() {
        seedExpired();
        var p = row();
        p.setExpiresAt(OffsetDateTime.now().plusMinutes(2));
        pending.saveAndFlush(p);
    }
    private void assertFiveMinutes() {
        assertThat(row().getExpiresAt()).isBetween(OffsetDateTime.now().plusSeconds(290), OffsetDateTime.now().plusSeconds(301));
    }
    private void assertSpent(String policy, String identity) throws Exception {
        var value = json.readTree(raw(key(policy, identity)));
        assertThat(value.get("credit").asLong()).isEqualTo(properties.policies().get(policy).capacityCredit()
                - properties.policies().get(policy).periodMs());
    }
    private void assertDebit(String policy, String before, String after) throws Exception {
        var b = json.readTree(before); var a = json.readTree(after); var p = properties.policies().get(policy);
        long elapsed = a.get("updatedAtMs").asLong() - b.get("updatedAtMs").asLong();
        assertThat(elapsed).isBetween(0L, 10000L);
        assertThat(a.get("credit").asLong()).isEqualTo(Math.min(p.capacityCredit(), b.get("credit").asLong()
                + elapsed * p.refillTokens()) - p.periodMs());
    }
    private double allowed(String policy) {
        return meters.find("learne.ratelimit.requests").tags("policy", policy, "outcome", "allowed")
                .counters().stream().mapToDouble(c -> c.count()).sum();
    }
    private java.net.http.HttpResponse<String> http(String body, String bearer) throws Exception {
        var builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/auth/register/request-otp"))
                .header("Content-Type", "application/json");
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer).header("X-Forwarded-For", "203.0.113.99");
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            return client.send(builder.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void realServerConsumesOnceAndDenialPrecedesValidJwtAndAllRegistrationWork() throws Exception {
        double before = allowed(SEND);
        assertThat(http(requestBody(email), null).statusCode()).isEqualTo(200);
        assertThat(allowed(SEND) - before).isEqualTo(1);
        assertSpent(SEND, "127.0.0.1");
        var saved = snapshot();
        // Obtain a genuinely signed token through the existing login API, before clearing observations.
        var user = new User(); user.setEmail("bearer-" + email); user.setFullName("Bearer");
        user.setPasswordHash(passwords.encode("Password123")); user.setRole(UserRole.STUDENT); user.setActive(true);
        users.saveAndFlush(user);
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", "Password123"))))
                .andExpect(status().isOk()).andReturn();
        String bearer = json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        assertThat(jwt.parseUserId(bearer)).isEqualTo(user.getId());
        exhausted(SEND, "127.0.0.1");
        String state = raw(key(SEND, "127.0.0.1"));
        clearObservations();
        var response = http(requestBody(email), bearer);
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(response.headers().firstValue("Retry-After")).hasValueSatisfying(v -> assertThat(Long.parseLong(v)).isPositive());
        verifyNoInteractions(jwt, users, pending, passwords, generator, mail);
        assertThat(raw(key(SEND, "127.0.0.1"))).isEqualTo(state);
        assertThat(raw(key(SEND, "203.0.113.99"))).isNull();
        assertThat(snapshot()).isEqualTo(saved);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void malformedAndInvalidDtoConsumeIpOnlyAndRealServerErrorDispatchKeepsExisting401() throws Exception {
        double before = allowed(SEND);
        postRoute("request-otp", "{invalid").andExpect(status().isBadRequest());
        String first = raw(key(SEND, ip));
        request("not-an-email").andExpect(status().isBadRequest());
        assertDebit(SEND, first, raw(key(SEND, ip)));
        // Actual Tomcat redispatches invalid JSON to the existing secured /error endpoint.
        assertThat(http("{invalid", null).statusCode()).isEqualTo(401);
        assertThat(allowed(SEND) - before).isEqualTo(3);
        verifyNoInteractions(users, pending, passwords, generator, mail);
        assertThat(raw(key(EMAIL, email))).isNull();
        assertThat(pending.count()).isZero();
    }

    @Test
    void requestThenExpiredResendSharesCaseCanonicalEmailAcrossIpsWithoutMutationOrRefund() throws Exception {
        request(email).andExpect(status().isOk()).andExpect(jsonPath("$.otp").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist()).andExpect(jsonPath("$.email").doesNotExist());
        assertFiveMinutes(); assertSpent(EMAIL, email); assertSpent(SEND, ip);
        assertThat(passwords.matches("Password123", row().getPasswordHash())).isTrue();
        assertThat(passwords.matches(delivered.getFirst(), row().getOtpHash())).isTrue();
        assertThat(users.count()).isZero();
        expire(); var before = snapshot(); String emailBefore = raw(key(EMAIL, email));
        ip = "198.51.100.101";
        clearObservations();
        resend(email.toUpperCase(Locale.ROOT)).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verifyNoInteractions(passwords, generator, mail);
        verify(pending, never()).save(any()); verify(pending, never()).delete(any());
        verify(pending, never()).flush();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(raw(key(EMAIL, email.toUpperCase(Locale.ROOT)))).isEqualTo(emailBefore);
        assertThat(mailAttempts.get()).isEqualTo(1); assertSpent(SEND, ip);
    }

    @Test
    void resendThenRequestSharesEmailBudgetAndKeepsPendingUntouched() throws Exception {
        seedExpired(); resend(email.toUpperCase(Locale.ROOT)).andExpect(status().isOk());
        assertFiveMinutes(); assertThat(row().getAttempts()).isZero();
        var before = snapshot(); String budget = raw(key(EMAIL, email)); String ipBefore = raw(key(SEND, ip));
        clearObservations();
        request(email).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        var order = inOrder(users, limiter);
        order.verify(users).findByEmail(email);
        order.verify(limiter).requireAllowed(EMAIL, RateLimitDimension.EMAIL, email);
        verifyNoInteractions(passwords, generator, mail);
        verify(pending, never()).delete(any()); verify(pending, never()).save(any());
        verify(pending, never()).flush();
        assertThat(snapshot()).isEqualTo(before); assertThat(raw(key(EMAIL, email))).isEqualTo(budget);
        assertDebit(SEND, ipBefore, raw(key(SEND, ip)));
        assertThat(mailAttempts.get()).isEqualTo(1); assertThat(users.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"weak", "mismatch", "existing", "missing", "live"})
    void businessRejectionsSpendIpButNotEmail(String kind) throws Exception {
        if (kind.equals("existing")) {
            var u = new User(); u.setEmail(email); u.setFullName("Existing"); u.setPasswordHash("unused"); u.setRole(UserRole.STUDENT); users.saveAndFlush(u);
        }
        if (kind.equals("live")) { seedExpired(); var p = row(); p.setExpiresAt(OffsetDateTime.now().plusMinutes(5)); pending.saveAndFlush(p); }
        clearObservations();
        if (kind.equals("missing") || kind.equals("live")) resend(email).andExpect(status().isBadRequest());
        else {
            String body = requestBody(email);
            if (kind.equals("weak")) body = body.replace("Password123", "password");
            if (kind.equals("mismatch")) body = body.replace("\"confirmPassword\":\"Password123\"", "\"confirmPassword\":\"Password456\"");
            postRoute("request-otp", body).andExpect(kind.equals("existing") ? status().isConflict() : status().isBadRequest());
        }
        assertSpent(SEND, ip); assertThat(raw(key(EMAIL, email))).isNull();
        verify(limiter, never()).requireAllowed(eq(EMAIL), any(), any());
        verifyNoInteractions(passwords, generator, mail);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ip", "request-email", "resend-email"})
    void corruptRealLuaStateFailsClosedBeforeSideEffects(String guard) throws Exception {
        seedExpired(); var before = snapshot(); long userCount = users.count();
        String corruptKey = guard.equals("ip") ? key(SEND, ip) : key(EMAIL, email);
        put(corruptKey, "{malformed"); clearObservations();
        (guard.equals("resend-email") ? resend(email) : request(email)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ABUSE_PROTECTION_UNAVAILABLE"));
        verifyNoInteractions(passwords, generator, mail);
        verify(pending, never()).save(any()); verify(pending, never()).delete(any());
        verify(pending, never()).flush();
        if (guard.equals("ip")) verifyNoInteractions(users, pending);
        else {
            var order = inOrder(users, pending, limiter);
            if (guard.equals("request-email")) order.verify(users).findByEmail(email);
            else order.verify(pending).findByEmail(email);
            order.verify(limiter).requireAllowed(EMAIL, RateLimitDimension.EMAIL, email);
            assertSpent(SEND, ip);
        }
        assertThat(raw(corruptKey)).isEqualTo("{malformed");
        assertThat(snapshot()).isEqualTo(before); assertThat(users.count()).isEqualTo(userCount);
    }

    @Test
    void eligibleRequestReplacesActivePendingWithNewIdentityCredentialsAndOneMail() throws Exception {
        seedActive(); var old = row();
        assertThat(raw(key(EMAIL, email))).isNull();
        clearObservations();
        postRoute("request-otp", json.writeValueAsString(new RequestRegistrationOtpRequest(
                " Replacement Student ", email, "Replacement456", "Replacement456")))
                .andExpect(status().isOk());
        var replacement = row();
        assertThat(pending.count()).isEqualTo(1);
        assertThat(replacement.getId()).isNotEqualTo(old.getId());
        assertThat(replacement.getCreatedAt()).isAfter(old.getCreatedAt());
        assertThat(replacement.getEmail()).isEqualTo(email);
        assertThat(replacement.getFullName()).isEqualTo("Replacement Student");
        assertThat(replacement.getPasswordHash()).isNotEqualTo(old.getPasswordHash());
        assertThat(passwords.matches("Replacement456", replacement.getPasswordHash())).isTrue();
        assertThat(passwords.matches("Password123", replacement.getPasswordHash())).isFalse();
        assertThat(replacement.getOtpHash()).isNotEqualTo(old.getOtpHash());
        assertThat(delivered).hasSize(1);
        assertThat(passwords.matches(delivered.getFirst(), replacement.getOtpHash())).isTrue();
        assertFiveMinutes(); assertThat(replacement.getAttempts()).isZero();
        assertThat(mailAttempts.get()).isEqualTo(1); assertThat(users.count()).isZero();
        assertSpent(EMAIL, email); assertSpent(SEND, ip);
        verify(mail).sendRegistrationOtp(email, delivered.getFirst());
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "resend", "replacement"})
    void smtpFailureRollsBackPendingButNeverRefundsEmailOrRetriesMail(String route) throws Exception {
        if (route.equals("resend")) seedExpired();
        if (route.equals("replacement")) seedActive();
        var before = !route.equals("request") ? snapshot() : List.of();
        assertThat(raw(key(EMAIL, email))).isNull();
        doAnswer(call -> { mailAttempts.incrementAndGet(); throw new MailSendException("fixture SMTP failure"); })
                .when(mail).sendRegistrationOtp(anyString(), anyString());
        assertThatThrownBy(() -> { if (route.equals("resend")) resend(email); else request(email); })
                .hasRootCauseInstanceOf(MailSendException.class);
        if (!route.equals("request")) assertThat(snapshot()).isEqualTo(before);
        else assertThat(pending.count()).isZero();
        assertThat(users.count()).isZero();
        assertThat(mailAttempts.get()).isEqualTo(1); assertThat(delivered).isEmpty(); assertSpent(EMAIL, email);
        String ipBefore = raw(key(SEND, ip)); String emailBefore = raw(key(EMAIL, email));
        clearObservations();
        request(email).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verifyNoInteractions(passwords, generator, mail);
        verify(pending, never()).flush();
        if (!route.equals("request")) assertThat(snapshot()).isEqualTo(before);
        else assertThat(pending.count()).isZero();
        assertThat(raw(key(EMAIL, email))).isEqualTo(emailBefore);
        assertDebit(SEND, ipBefore, raw(key(SEND, ip))); assertThat(mailAttempts.get()).isEqualTo(1);
    }

    @Test
    void concurrentEligibleRequestsUseAtomicEmailGuardWithOneMailAndSharedTransportIdentity() throws Exception {
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> { barrier.await(10, TimeUnit.SECONDS); return call.callRealMethod(); })
                .when(limiter).requireAllowed(EMAIL, RateLimitDimension.EMAIL, email);
        long start = redisMillis();
        List<Integer> statuses;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> request(email).andReturn().getResponse().getStatus());
            var b = executor.submit(() -> request(email.toUpperCase(Locale.ROOT)).andReturn().getResponse().getStatus());
            statuses = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        long elapsed = redisMillis() - start;
        assertThat(statuses).allMatch(s -> s == 200 || s == 429);
        assertThat(mailAttempts.get()).isBetween(1, (int) (1 + elapsed / 60000));
        if (elapsed < 60000) assertThat(statuses).containsExactlyInAnyOrder(200, 429);
        verify(users, times(2)).findByEmail(email);
        assertThat(pending.count()).isEqualTo(1); assertThat(users.count()).isZero();
        var store = new RedisTokenBucketStore(singletonTransport);
        var first = new RateLimitService(properties, keys, () -> store, context.getBean(RateLimitMetrics.class));
        var second = new RateLimitService(properties, keys, () -> store, context.getBean(RateLimitMetrics.class));
        // Stabilize only this identity-proof fixture against refill during slow CI pauses.
        exhausted(EMAIL, email);
        assertThat(first.consume(EMAIL, RateLimitDimension.EMAIL, email).outcome()).isEqualTo(RateLimitDecision.Outcome.REJECTED);
        assertThat(second.consume(EMAIL, RateLimitDimension.EMAIL, email.toUpperCase(Locale.ROOT)).outcome()).isEqualTo(RateLimitDecision.Outcome.REJECTED);
    }

    @Test
    void verifyDenialDoesNotResetDbAttemptsAndFiveWrongCodesStayBusiness429() throws Exception {
        request(email).andExpect(status().isOk()); String budget = raw(key(EMAIL, email));
        String wrong = delivered.getFirst().equals("000000") ? "111111" : "000000";
        for (int i = 1; i <= 2; i++) { verifyOtp(wrong).andExpect(status().isBadRequest()); assertThat(row().getAttempts()).isEqualTo(i); }
        var before = snapshot(); exhausted(VERIFY, ip); String verifyState = raw(key(VERIFY, ip));
        clearObservations();
        verifyOtp(delivered.getFirst()).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verifyNoInteractions(users, pending, passwords, generator, mail);
        assertThat(snapshot()).isEqualTo(before); assertThat(raw(key(VERIFY, ip))).isEqualTo(verifyState);
        ip = "198.51.100.102";
        for (int i = 3; i <= 5; i++) {
            verifyOtp(wrong).andExpect(i == 5 ? status().isTooManyRequests() : status().isBadRequest())
                    .andExpect(jsonPath("$.code").doesNotExist());
            assertThat(row().getAttempts()).isEqualTo(i);
        }
        verifyOtp(delivered.getFirst()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many invalid OTP attempts. Please request a new code."))
                .andExpect(jsonPath("$.code").doesNotExist());
        assertThat(raw(key(EMAIL, email))).isEqualTo(budget); assertThat(users.count()).isZero();
        verifyNoInteractions(mail); assertThat(mailAttempts.get()).isEqualTo(1);
    }

    @Test
    void validVerifyAloneCreatesUserAndNeverTouchesAbsentEmailBudgetOrSender() throws Exception {
        seedExpired(); var p = row(); p.setExpiresAt(OffsetDateTime.now().plusMinutes(5)); pending.saveAndFlush(p);
        clearObservations();
        verifyOtp("123456").andExpect(status().isOk());
        assertThat(users.findByEmail(email)).isPresent(); assertThat(pending.count()).isZero();
        assertThat(raw(key(EMAIL, email))).isNull(); assertThat(raw(key(SEND, ip))).isNull(); assertSpent(VERIFY, ip);
        verifyNoInteractions(generator, mail); assertThat(mailAttempts.get()).isZero();
    }

    @Test
    void expiredVerifyKeepsExpiryAttemptsAndAbsentSendBudget() throws Exception {
        seedExpired(); var before = snapshot(); clearObservations();
        verifyOtp("123456").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("OTP has expired."));
        assertThat(snapshot()).isEqualTo(before); assertThat(users.count()).isZero();
        assertThat(raw(key(EMAIL, email))).isNull(); assertSpent(VERIFY, ip);
        verifyNoInteractions(passwords, generator, mail);
    }

    @Test
    void dtoValidUnicodeBeyond254Utf8BytesReachesRealGuardHashAndCapturedSend() throws Exception {
        email = "é".repeat(32) + "@" + ("é".repeat(30) + ".").repeat(3) + "é".repeat(30);
        assertThat(email.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(254);
        assertThat(email.length()).isLessThanOrEqualTo(255);
        assertThat(validator.validate(new RequestRegistrationOtpRequest("Unicode", email, "Password123", "Password123"))).isEmpty();
        request(email).andExpect(status().isOk());
        assertThat(row().getEmail()).isEqualTo(email); assertSpent(EMAIL, email);
        assertThat(passwords.matches(delivered.getFirst(), row().getOtpHash())).isTrue();
        assertThat(mailAttempts.get()).isEqualTo(1); assertThat(users.count()).isZero();
    }

    @Test
    void unrelatedRoutesAndOptionsLeaveRegistrationBudgetsUntouched() throws Exception {
        postRoute("request-otp", "{invalid").andExpect(status().isBadRequest());
        exhausted(EMAIL, email); exhausted(VERIFY, ip);
        String send = raw(key(SEND, ip)), emailState = raw(key(EMAIL, email)), verify = raw(key(VERIFY, ip));
        mvc.perform(options("/api/auth/register/request-otp").with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk());
        for (String route : List.of("login", "refresh", "logout")) {
            mvc.perform(post("/api/auth/" + route).with(r -> { r.setRemoteAddr(ip); return r; })
                    .contentType(MediaType.APPLICATION_JSON).content("{}"));
        }
        assertThat(raw(key(SEND, ip))).isEqualTo(send); assertThat(raw(key(EMAIL, email))).isEqualTo(emailState);
        assertThat(raw(key(VERIFY, ip))).isEqualTo(verify);
        assertThat(raw(key("login-ip", ip))).isNotNull(); verifyNoInteractions(mail);
    }
}
