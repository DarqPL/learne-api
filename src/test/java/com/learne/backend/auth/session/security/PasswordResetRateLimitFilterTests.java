package com.learne.backend.auth.session.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.learne.backend.ratelimit.*;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class PasswordResetRateLimitFilterTests {
    private final ClientIpResolver resolver = mock(ClientIpResolver.class);
    private final RateLimitService service = mock(RateLimitService.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private PasswordResetRateLimitFilter filter(boolean enabled, ClientIpResolver ipResolver) {
        return new PasswordResetRateLimitFilter(new RateLimitProperties(enabled, null, null, null, null, null, null), ipResolver, service,
                new RateLimitErrorResponseFactory(new ObjectMapper()));
    }

    private MockHttpServletRequest request(String route) {
        return new MockHttpServletRequest("POST", "/api/auth/password-reset/" + route);
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "confirm"})
    void guardsEachRouteOnceBeforeDownstreamIncludingNestedInvocation(String route) throws Exception {
        var request = request(route);
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        var filter = filter(true, resolver);
        filter.doFilter(request, response, (req, res) -> filter.doFilter(req, res, chain));
        var order = inOrder(resolver, service, chain);
        order.verify(resolver).resolve(request);
        order.verify(service).requireAllowed("password-reset-" + route + "-ip", RateLimitDimension.IP, "192.0.2.1");
        order.verify(chain).doFilter(request, response);
        verifyNoMoreInteractions(resolver, service, chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "confirm"})
    void matchesUnderContextPath(String route) throws Exception {
        var request = new MockHttpServletRequest("POST", "/learne/api/auth/password-reset/" + route);
        request.setContextPath("/learne");
        request.setServletPath("/api/auth/password-reset/" + route);
        when(resolver.resolve(request)).thenReturn("192.0.2.2");
        filter(true, resolver).doFilter(request, response, chain);
        verify(service).requireAllowed("password-reset-" + route + "-ip", RateLimitDimension.IP, "192.0.2.2");
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "confirm"})
    void disabledSkipsResolverAndGuardBeforeInvalidPeer(String route) throws Exception {
        var request = request(route);
        request.setRemoteAddr("invalid-peer");
        filter(false, resolver).doFilter(request, response, chain);
        verifyNoInteractions(resolver, service);
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @CsvSource({"OPTIONS,/api/auth/password-reset/request", "OPTIONS,/api/auth/password-reset/confirm",
            "GET,/api/auth/password-reset/request", "GET,/api/auth/password-reset/confirm",
            "PUT,/api/auth/password-reset/request", "PATCH,/api/auth/password-reset/confirm",
            "DELETE,/api/auth/password-reset/request", "HEAD,/api/auth/password-reset/confirm",
            "POST,/api/auth/login", "POST,/api/auth/refresh", "POST,/api/auth/logout",
            "POST,/api/auth/register/request-otp", "POST,/api/auth/register/resend-otp", "POST,/api/auth/register/verify-otp",
            "POST,/api/auth/password-reset/request/extra", "POST,/api/auth/password-reset/confirm/extra",
            "POST,/api/auth/password-reset/request-other", "POST,/other/api/auth/password-reset/confirm",
            "POST,/api/auth/password-reset/request/", "POST,/api/auth/password-reset/confirm/"})
    void unrelatedRequestsSkipGuard(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        filter(true, resolver).doFilter(request, response, chain);
        verifyNoInteractions(resolver, service);
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @CsvSource({"request,ASYNC", "request,ERROR", "confirm,ASYNC", "confirm,ERROR"})
    void defaultAsyncAndErrorDispatchesSkipGuard(String route, DispatcherType dispatcher) throws Exception {
        var request = request(route);
        request.setDispatcherType(dispatcher);
        if (dispatcher == DispatcherType.ERROR) request.setAttribute("jakarta.servlet.error.request_uri", request.getRequestURI());
        filter(true, resolver).doFilter(request, response, chain);
        verifyNoInteractions(resolver, service);
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @CsvSource({"request,false", "confirm,false", "request,true", "confirm,true"})
    void guardFailuresUseSharedHttpWriterAndStopChain(String route, boolean unavailable) throws Exception {
        var request = request(route);
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        doThrow(unavailable ? RateLimitException.unavailable() : RateLimitException.rejected(1501))
                .when(service).requireAllowed("password-reset-" + route + "-ip", RateLimitDimension.IP, "192.0.2.1");
        filter(true, resolver).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(unavailable ? 503 : 429);
        assertThat(response.getHeader("Retry-After")).isEqualTo(unavailable ? null : "2");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains(unavailable ? "ABUSE_PROTECTION_UNAVAILABLE" : "RATE_LIMITED");
        verifyNoInteractions(chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "confirm"})
    void invalidPeerReturns503BeforeGuard(String route) throws Exception {
        var request = request(route);
        request.setRemoteAddr("invalid-peer");
        filter(true, new ClientIpResolver(List.of())).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isNull();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("ABUSE_PROTECTION_UNAVAILABLE");
        verifyNoInteractions(service, chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "confirm"})
    void downstreamRateLimitExceptionPropagatesWithoutResettingResponse(String route) throws Exception {
        var request = request(route);
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

    @ParameterizedTest
    @ValueSource(strings = {"request", "confirm"})
    void unexpectedGuardExceptionsPropagate(String route) throws Exception {
        var request = request(route);
        when(resolver.resolve(request)).thenReturn("192.0.2.1");
        var failure = new IllegalStateException("unexpected");
        doThrow(failure).when(service).requireAllowed("password-reset-" + route + "-ip", RateLimitDimension.IP, "192.0.2.1");
        assertThatThrownBy(() -> filter(true, resolver).doFilter(request, response, chain)).isSameAs(failure);
        verifyNoInteractions(chain);
    }
}
