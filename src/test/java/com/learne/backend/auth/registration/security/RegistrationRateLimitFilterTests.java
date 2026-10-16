package com.learne.backend.auth.registration.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.learne.backend.ratelimit.*;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class RegistrationRateLimitFilterTests {
    private final ClientIpResolver resolver = mock(ClientIpResolver.class);
    private final RateLimitService service = mock(RateLimitService.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private RegistrationRateLimitFilter filter(boolean enabled, ClientIpResolver ipResolver) {
        return new RegistrationRateLimitFilter(new RateLimitProperties(enabled, null, null, null, null, null, null),
                ipResolver, service, new RateLimitErrorResponseFactory(new ObjectMapper()));
    }

    private MockHttpServletRequest request(String route) {
        return new MockHttpServletRequest("POST", "/api/auth/register/" + route);
    }

    @ParameterizedTest
    @CsvSource({"request-otp,register-send-ip", "resend-otp,register-send-ip", "verify-otp,register-verify-ip"})
    void guardsExactRoutesBeforeDownstreamEvenWithMalformedBody(String route, String policy) throws Exception {
        var request = request(route);
        request.setContentType("application/json");
        request.setContent("{invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        filter(true, resolver).doFilter(request, response, chain);
        var order = inOrder(resolver, service, chain);
        order.verify(resolver).resolve(request);
        order.verify(service).requireAllowed(policy, RateLimitDimension.IP, "192.0.2.1");
        order.verify(chain).doFilter(request, response);
        verifyNoMoreInteractions(resolver, service, chain);
    }

    @ParameterizedTest
    @CsvSource({"request-otp,register-send-ip", "resend-otp,register-send-ip", "verify-otp,register-verify-ip"})
    void matchesUnderContextPath(String route, String policy) throws Exception {
        var request = new MockHttpServletRequest("POST", "/learne/api/auth/register/" + route);
        request.setContextPath("/learne");
        request.setServletPath("/api/auth/register/" + route);
        when(resolver.resolve(request)).thenReturn("192.0.2.2");
        filter(true, resolver).doFilter(request, response, chain);
        verify(service).requireAllowed(policy, RateLimitDimension.IP, "192.0.2.2");
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"request-otp", "resend-otp", "verify-otp"})
    void disabledSkipsResolverAndGuardEvenWithInvalidPeer(String route) throws Exception {
        var request = request(route);
        request.setRemoteAddr("invalid-peer");
        filter(false, resolver).doFilter(request, response, chain);
        verifyNoInteractions(resolver, service);
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @CsvSource({"OPTIONS,/api/auth/register/request-otp", "OPTIONS,/api/auth/register/resend-otp",
            "OPTIONS,/api/auth/register/verify-otp", "GET,/api/auth/register/request-otp",
            "PUT,/api/auth/register/resend-otp", "DELETE,/api/auth/register/verify-otp",
            "PATCH,/api/auth/register/request-otp", "HEAD,/api/auth/register/verify-otp",
            "POST,/api/auth/login", "POST,/api/auth/refresh", "POST,/api/auth/logout",
            "POST,/api/auth/password-reset/request", "POST,/api/auth/register/request-otp/extra",
            "POST,/api/auth/register/resend-otp/", "POST,/api/auth/register/verify-otp-other",
            "POST,/other/api/auth/register/request-otp"})
    void unrelatedRequestsSkipResolverAndGuard(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        filter(true, resolver).doFilter(request, response, chain);
        verifyNoInteractions(resolver, service);
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @EnumSource(value = DispatcherType.class, names = {"ASYNC", "ERROR"})
    void defaultAsyncAndErrorDispatchesSkipGuard(DispatcherType dispatcher) throws Exception {
        var request = request("request-otp");
        request.setDispatcherType(dispatcher);
        if (dispatcher == DispatcherType.ERROR) request.setAttribute("jakarta.servlet.error.request_uri", request.getRequestURI());
        filter(true, resolver).doFilter(request, response, chain);
        verifyNoInteractions(resolver, service);
        verify(chain).doFilter(request, response);
    }

    @Test
    void nestedInvocationDoesNotConsumeTwice() throws Exception {
        var request = request("request-otp");
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        var filter = filter(true, resolver);
        filter.doFilter(request, response, (req, res) -> filter.doFilter(req, res, chain));
        verify(resolver, times(1)).resolve(request);
        verify(service, times(1)).requireAllowed("register-send-ip", RateLimitDimension.IP, "192.0.2.1");
        verify(chain, times(1)).doFilter(request, response);
    }

    @ParameterizedTest
    @CsvSource({"request-otp,register-send-ip", "resend-otp,register-send-ip", "verify-otp,register-verify-ip"})
    void rejectedGuardWritesShared429AndStopsDownstream(String route, String policy) throws Exception {
        var request = request(route);
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        doThrow(RateLimitException.rejected(1501)).when(service).requireAllowed(policy, RateLimitDimension.IP, "192.0.2.1");
        filter(true, resolver).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"").doesNotContain(policy, "192.0.2.1");
        verifyNoInteractions(chain);
    }

    @Test
    void unavailableGuardWritesShared503AndStopsDownstream() throws Exception {
        var request = request("verify-otp");
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        doThrow(RateLimitException.unavailable()).when(service).requireAllowed("register-verify-ip", RateLimitDimension.IP, "192.0.2.1");
        filter(true, resolver).doFilter(request, response, chain);
        assertUnavailable();
        verifyNoInteractions(chain);
    }

    @Test
    void invalidPeerUsesShared503WithoutCallingGuard() throws Exception {
        var request = request("request-otp");
        request.setRemoteAddr("invalid-peer");
        filter(true, new ClientIpResolver(List.of())).doFilter(request, response, chain);
        assertUnavailable();
        verifyNoInteractions(service, chain);
    }

    private void assertUnavailable() throws Exception {
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isNull();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("ABUSE_PROTECTION_UNAVAILABLE");
    }

    @ParameterizedTest
    @CsvSource({"192.0.2.3,192.0.2.3", "10.0.0.1,198.51.100.9"})
    void usesExistingTrustedProxyResolution(String peer, String expected) throws Exception {
        var request = request("request-otp");
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", "198.51.100.9");
        filter(true, new ClientIpResolver(List.of("10.0.0.0/8"))).doFilter(request, response, chain);
        verify(service).requireAllowed("register-send-ip", RateLimitDimension.IP, expected);
    }

    @Test
    void downstreamRateLimitExceptionPropagatesWithoutResettingResponse() throws Exception {
        var request = request("request-otp");
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        var failure = RateLimitException.rejected(1000);
        doAnswer(invocation -> {
            response.setStatus(202);
            response.getWriter().write("downstream");
            throw failure;
        }).when(chain).doFilter(request, response);
        assertThatThrownBy(() -> filter(true, resolver).doFilter(request, response, chain)).isSameAs(failure);
        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getContentAsString()).isEqualTo("downstream");
    }

    @Test
    void unexpectedGuardExceptionPropagates() throws Exception {
        var request = request("request-otp");
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        var failure = new IllegalStateException("unexpected");
        doThrow(failure).when(service).requireAllowed("register-send-ip", RateLimitDimension.IP, "192.0.2.1");
        assertThatThrownBy(() -> filter(true, resolver).doFilter(request, response, chain)).isSameAs(failure);
        verifyNoInteractions(chain);
    }
}
