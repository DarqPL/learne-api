package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learne.backend.ai.grammar.service.GrammarCheckFailedException;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.shared.api.GlobalExceptionHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.cors.allowed-origins=http://localhost:5173")
@AutoConfigureMockMvc
@WithMockUser
@Import(RateLimitHttpContractTests.ContractController.class)
class RateLimitHttpContractTests {
    private static final String REJECTED = "{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests. Please try again later.\"}";
    private static final String UNAVAILABLE = "{\"code\":\"ABUSE_PROTECTION_UNAVAILABLE\",\"message\":\"Service temporarily unavailable. Please try again later.\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("corsConfigurationSource")
    private CorsConfigurationSource corsSource;

    @Autowired
    private RateLimitErrorResponseFactory responseFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    @ParameterizedTest
    @CsvSource({"1,1", "1000,1", "1001,2", "86460000,86460"})
    void filterAndMvcRejectionsHaveIdenticalBytesAndHeaders(long delay, String seconds) throws Exception {
        assertFilterMatchesMvc("rejected", delay, 429, REJECTED, seconds);
    }

    @Test
    void filterAndMvcUnavailableHaveIdenticalBytesAndNoRetryAfter() throws Exception {
        assertFilterMatchesMvc("unavailable", 0, 503, UNAVAILABLE, null);
    }

    private void assertFilterMatchesMvc(String kind, long delay, int statusCode, String body, String retryAfter)
            throws Exception {
        var filterMvc = MockMvcBuilders.standaloneSetup(new ContractController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(handlerAdapter.getMessageConverters().toArray(
                        org.springframework.http.converter.HttpMessageConverter[]::new))
                .addFilters(new ContractFilter(responseFactory))
                .build();
        var filtered = filterMvc.perform(get("/test/filter/" + kind).param("delay", Long.toString(delay))
                        .param("redisKey", "rl:private-key").param("accountId", "secret-account"))
                .andExpect(status().is(statusCode))
                .andExpect(content().bytes(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse();
        var mvc = mockMvc.perform(get("/test/rate-limit/" + kind).param("delay", Long.toString(delay)))
                .andReturn().getResponse();
        assertThat(filtered.getContentAsByteArray()).isEqualTo(mvc.getContentAsByteArray());
        assertThat(filtered.getContentType()).isEqualTo(mvc.getContentType());
        assertThat(filtered.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(retryAfter);
        assertThat(mvc.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(retryAfter);
    }

    @Test
    void committedResponseIsUntouched() throws Exception {
        var response = new MockHttpServletResponse();
        response.setStatus(202);
        response.setHeader(HttpHeaders.RETRY_AFTER, "existing");
        response.getWriter().write("already sent");
        response.flushBuffer();
        responseFactory.write(response, RateLimitException.unavailable());
        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("existing");
        assertThat(response.getContentAsString()).isEqualTo("already sent");
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void writerReplacesUncommittedBodyAndStaleHeadersPreservingCors(boolean writerSelected) throws Exception {
        var response = new MockHttpServletResponse();
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.setHeader(HttpHeaders.RETRY_AFTER, "999");
        response.setContentLength(999);
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");
        if (writerSelected) {
            response.getWriter().write("private partial body");
        } else {
            response.getOutputStream().write("private partial body".getBytes(StandardCharsets.UTF_8));
            response.setWriterAccessAllowed(false);
        }
        responseFactory.write(response, RateLimitException.unavailable());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsByteArray()).isEqualTo(UNAVAILABLE.getBytes(StandardCharsets.UTF_8));
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH))
                .isEqualTo(Integer.toString(UNAVAILABLE.getBytes(StandardCharsets.UTF_8).length));
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("http://localhost:5173");
    }

    @Test
    void writerHonorsInjectedJackson3MapperConfiguration() throws Exception {
        var configuredMapper = objectMapper.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
        var response = new MockHttpServletResponse();
        new RateLimitErrorResponseFactory(configuredMapper).write(response, RateLimitException.rejected(1));
        var expected = configuredMapper.writeValueAsBytes(RateLimitErrorResponseFactory.response(
                RateLimitException.rejected(1)).getBody());
        assertThat(response.getContentAsByteArray()).isEqualTo(expected);
        assertThat(response.getContentAsString()).contains("\n");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
    }

    @ParameterizedTest
    @CsvSource({"true,429", "false,503"})
    void preselectedUtf16WriterIsReplacedWithExactUtf8NetworkBytes(boolean rejected, int statusCode) throws Exception {
        var response = new MockHttpServletResponse();
        response.setCharacterEncoding(StandardCharsets.UTF_16);
        response.setContentType("text/plain;charset=UTF-16");
        response.setContentLength(999);
        response.getWriter().write("private partial body");

        responseFactory.write(response, rejected ? RateLimitException.rejected(1001) : RateLimitException.unavailable());

        byte[] expected = (rejected ? REJECTED : UNAVAILABLE).getBytes(StandardCharsets.UTF_8);
        assertThat(response.getContentAsByteArray()).isEqualTo(expected);
        assertThat(response.getContentLength()).isEqualTo(expected.length);
        assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo(Integer.toString(expected.length));
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getStatus()).isEqualTo(statusCode);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(rejected ? "2" : null);
    }

    @Test
    void replacingBodyDropsEntityMetadataButPreservesMultivaluedTransportAndSecurityHeaders() throws Exception {
        var response = new MockHttpServletResponse();
        var staleHeaders = java.util.List.of("Content-Encoding", "Content-Range", "Content-Language",
                "Content-Location", "Content-Disposition", "Content-MD5", "Content-Digest", "Repr-Digest", "Digest",
                "Transfer-Encoding", "Trailer", "ETag", "Last-Modified", "Expires", "Age", "Accept-Ranges");
        staleHeaders.forEach(name -> response.setHeader(name, "stale"));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=3600");
        response.setHeader(HttpHeaders.RETRY_AFTER, "999");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        response.addHeader(HttpHeaders.VARY, "Origin");
        response.addHeader(HttpHeaders.VARY, "Accept-Encoding");
        response.addHeader(HttpHeaders.SET_COOKIE, "first=value; HttpOnly; Secure");
        response.addHeader(HttpHeaders.SET_COOKIE, "second=value; SameSite=Strict");
        response.setHeader("Content-Security-Policy", "default-src 'none'");
        response.setHeader("Strict-Transport-Security", "max-age=31536000");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Request-ID", "request-123");
        var preservedNames = java.util.List.of(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, HttpHeaders.VARY, HttpHeaders.SET_COOKIE,
                "Content-Security-Policy", "Strict-Transport-Security", "X-Content-Type-Options", "X-Frame-Options", "X-Request-ID");
        var preserved = new java.util.LinkedHashMap<String, java.util.List<String>>();
        preservedNames.forEach(name -> preserved.put(name, java.util.List.copyOf(response.getHeaders(name))));

        responseFactory.write(response, RateLimitException.unavailable());

        staleHeaders.forEach(name -> assertThat(response.getHeader(name)).as(name).isNull());
        preserved.forEach((name, values) -> assertThat(response.getHeaders(name)).as(name).containsExactlyElementsOf(values));
        assertThat(response.getContentAsByteArray()).isEqualTo(UNAVAILABLE.getBytes(StandardCharsets.UTF_8));
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @ParameterizedTest
    @CsvSource({"1,1", "1000,1", "1001,2", "86460000,86460"})
    void rejectedMvcResponseHasOnlyPublicContract(long delay, String seconds) throws Exception {
        mockMvc.perform(get("/test/rate-limit/rejected").param("delay", Long.toString(delay))
                        .param("redisKey", "rl:private-key").param("accountId", "secret-account"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, seconds))
                .andExpect(content().bytes(REJECTED.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void unavailableMvcResponseHasNoRetryAfter() throws Exception {
        mockMvc.perform(get("/test/rate-limit/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(content().bytes(UNAVAILABLE.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void zeroDelayIsInvalidRatherThanAnHttpRejection() {
        assertThatThrownBy(() -> RateLimitException.rejected(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"quota,Daily quota exceeded.", "otp,Please wait before requesting another OTP."})
    void existingApiException429RemainsMessageOnly(String kind, String message) throws Exception {
        mockMvc.perform(get("/test/rate-limit/" + kind))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(content().json("{\"message\":\"" + message + "\"}",
                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void grammarFailureRetainsExistingContract() throws Exception {
        mockMvc.perform(get("/test/rate-limit/grammar"))
                .andExpect(status().isBadGateway())
                .andExpect(content().json("{\"checkId\":42,\"status\":\"FAILED\",\"message\":\"AI unavailable\"}",
                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void actualCorsBeanPreservesConfigurationAndExposesRetryAfter() {
        var configuration = corsSource.getCorsConfiguration(new MockHttpServletRequest("GET", "/health"));
        assertThat(configuration).isNotNull();
        assertThat(configuration.getExposedHeaders()).contains(HttpHeaders.RETRY_AFTER);
        assertThat(configuration.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(configuration.getAllowedHeaders()).containsExactly("Authorization", "Content-Type", "Accept", "Origin",
                "X-Requested-With", "X-SePay-Signature", "X-SePay-Timestamp");
        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.getMaxAge()).isEqualTo(3600L);
    }

    @Test
    void corsPreflightAllowsCredentialsAndActualResponseExposesRetryAfter() throws Exception {
        mockMvc.perform(options("/test/rate-limit/rejected")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mockMvc.perform(get("/test/rate-limit/rejected").param("delay", "1001")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Retry-After, Location"));
    }

    @RestController
    static class ContractController {
        @GetMapping("/test/rate-limit/rejected")
        void rejected(@RequestParam long delay) {
            throw RateLimitException.rejected(delay);
        }

        @GetMapping("/test/rate-limit/unavailable")
        void unavailable() {
            throw RateLimitException.unavailable();
        }

        @GetMapping("/test/rate-limit/quota")
        void quota() {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Daily quota exceeded.");
        }

        @GetMapping("/test/rate-limit/otp")
        void otp() {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Please wait before requesting another OTP.");
        }

        @GetMapping("/test/rate-limit/grammar")
        void grammar() {
            throw new GrammarCheckFailedException(HttpStatus.BAD_GATEWAY, "AI unavailable", 42L);
        }
    }

    static class ContractFilter extends OncePerRequestFilter {
        private final RateLimitErrorResponseFactory factory;

        ContractFilter(RateLimitErrorResponseFactory factory) {
            this.factory = factory;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getRequestURI().startsWith("/test/filter/")) {
                RateLimitException exception = request.getRequestURI().endsWith("/rejected")
                        ? RateLimitException.rejected(Long.parseLong(request.getParameter("delay")))
                        : RateLimitException.unavailable();
                factory.write(response, exception);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
