package com.learne.backend.auth.session.security;

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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class LoginRateLimitFilterTests {
    private final ClientIpResolver resolver = mock(ClientIpResolver.class);
    private final RateLimitService service = mock(RateLimitService.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private LoginRateLimitFilter filter(boolean enabled, ClientIpResolver ipResolver) {
        return new LoginRateLimitFilter(new RateLimitProperties(enabled, null, null, null, null, null, null),
                ipResolver, service, new RateLimitErrorResponseFactory(new ObjectMapper()));
    }

    private MockHttpServletRequest login() {
        return new MockHttpServletRequest("POST", "/api/auth/login");
    }

    @Test
    void guardsLoginOnceBeforeDownstreamWork() throws Exception {
        var request = login();
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        filter(true, resolver).doFilter(request, response, chain);
        var order = inOrder(resolver, service, chain);
        order.verify(resolver).resolve(request);
        order.verify(service).requireAllowed("login-ip", RateLimitDimension.IP, "192.0.2.1");
        order.verify(chain).doFilter(request, response);
        verifyNoMoreInteractions(resolver, service, chain);
    }

    @Test
    void matchesLoginUnderContextPath() throws Exception {
        var request = new MockHttpServletRequest("POST", "/learne/api/auth/login");
        request.setContextPath("/learne");
        request.setServletPath("/api/auth/login");
        when(resolver.resolve(request)).thenReturn("192.0.2.2");
        filter(true, resolver).doFilter(request, response, chain);
        verify(service).requireAllowed("login-ip", RateLimitDimension.IP, "192.0.2.2");
        verify(chain).doFilter(request, response);
    }

    @Test
    void disabledSkipsResolverAndGuardEvenWithInvalidPeer() throws Exception {
        var request = login();
        request.setRemoteAddr("invalid-peer");
        filter(false, resolver).doFilter(request, response, chain);
        verifyNoInteractions(resolver, service);
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @CsvSource({"OPTIONS,/api/auth/login", "GET,/api/auth/login", "PUT,/api/auth/login",
            "DELETE,/api/auth/login", "PATCH,/api/auth/login", "HEAD,/api/auth/login",
            "POST,/api/auth/refresh", "POST,/api/auth/logout", "POST,/api/auth/login/extra",
            "POST,/other/api/auth/login", "POST,/api/auth/login-other"})
    void unrelatedRequestsSkipResolverAndGuard(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        filter(true, resolver).doFilter(request, response, chain);
        verifyNoInteractions(resolver, service);
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @EnumSource(value = DispatcherType.class, names = {"ASYNC", "ERROR"})
    void defaultAsyncAndErrorDispatchesSkipGuard(DispatcherType dispatcher) throws Exception {
        var request = login();
        request.setDispatcherType(dispatcher);
        if (dispatcher == DispatcherType.ERROR) request.setAttribute("jakarta.servlet.error.request_uri", "/api/auth/login");
        filter(true, resolver).doFilter(request, response, chain);
        verifyNoInteractions(resolver, service);
        verify(chain).doFilter(request, response);
    }

    @Test
    void nestedInvocationDoesNotConsumeTwice() throws Exception {
        var request = login();
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        var filter = filter(true, resolver);
        filter.doFilter(request, response, (req, res) -> filter.doFilter(req, res, chain));
        verify(service, times(1)).requireAllowed("login-ip", RateLimitDimension.IP, "192.0.2.1");
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void rejectedGuardWritesShared429AndStopsDownstream() throws Exception {
        var request = login();
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        doThrow(RateLimitException.rejected(1501)).when(service).requireAllowed("login-ip", RateLimitDimension.IP, "192.0.2.1");
        filter(true, resolver).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
        verifyNoInteractions(chain);
    }

    @Test
    void unavailableGuardWritesShared503AndStopsDownstream() throws Exception {
        var request = login();
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        doThrow(RateLimitException.unavailable()).when(service).requireAllowed("login-ip", RateLimitDimension.IP, "192.0.2.1");
        filter(true, resolver).doFilter(request, response, chain);
        assertUnavailable();
        verifyNoInteractions(chain);
    }

    @Test
    void invalidPeerUsesShared503WithoutCallingGuard() throws Exception {
        var request = login();
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

    @Test
    void untrustedForwardedHeaderCannotChangeGuardKey() throws Exception {
        var request = login();
        request.setRemoteAddr("192.0.2.3");
        request.addHeader("X-Forwarded-For", "198.51.100.9");
        filter(true, new ClientIpResolver(List.of("10.0.0.0/8"))).doFilter(request, response, chain);
        verify(service).requireAllowed("login-ip", RateLimitDimension.IP, "192.0.2.3");
    }

    @Test
    void trustedProxyUsesResolvedClientIp() throws Exception {
        var request = login();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.9");
        filter(true, new ClientIpResolver(List.of("10.0.0.0/8"))).doFilter(request, response, chain);
        verify(service).requireAllowed("login-ip", RateLimitDimension.IP, "198.51.100.9");
    }

    @Test
    void downstreamRateLimitExceptionPropagatesWithoutResettingResponse() throws Exception {
        var request = login();
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
        var request = login();
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        var failure = new IllegalStateException("unexpected");
        doThrow(failure).when(service).requireAllowed("login-ip", RateLimitDimension.IP, "192.0.2.1");
        assertThatThrownBy(() -> filter(true, resolver).doFilter(request, response, chain)).isSameAs(failure);
        verifyNoInteractions(chain);
    }
}
