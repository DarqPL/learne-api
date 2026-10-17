package com.learne.backend.auth.session.security;

import com.learne.backend.ratelimit.ClientIpResolver;
import com.learne.backend.ratelimit.RateLimitDimension;
import com.learne.backend.ratelimit.RateLimitErrorResponseFactory;
import com.learne.backend.ratelimit.RateLimitException;
import com.learne.backend.ratelimit.RateLimitProperties;
import com.learne.backend.ratelimit.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {
    private static final PathPatternRequestMatcher LOGIN = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, "/api/auth/login");

    private final RateLimitProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final RateLimitService rateLimitService;
    private final RateLimitErrorResponseFactory errorResponses;

    public LoginRateLimitFilter(RateLimitProperties properties, ClientIpResolver clientIpResolver,
            RateLimitService rateLimitService, RateLimitErrorResponseFactory errorResponses) {
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
        this.rateLimitService = rateLimitService;
        this.errorResponses = errorResponses;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !LOGIN.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String ip = clientIpResolver.resolve(request);
            rateLimitService.requireAllowed("login-ip", RateLimitDimension.IP, ip);
        } catch (RateLimitException exception) {
            errorResponses.write(response, exception);
            return;
        }
        chain.doFilter(request, response);
    }
}
