package com.learne.backend.auth.session.security;

import com.learne.backend.config.CorsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TrustedOriginFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of("/api/auth/refresh", "/api/auth/logout");

    private final Set<String> allowedOrigins;

    public TrustedOriginFilter(CorsProperties corsProperties) {
        this.allowedOrigins = Set.copyOf(corsProperties.allowedOrigins());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (requiresTrustedOrigin(request) && !hasTrustedOrigin(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean requiresTrustedOrigin(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod()) && PROTECTED_PATHS.contains(pathWithinApplication(request));
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    private boolean hasTrustedOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin)) {
            return false;
        }

        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer != null) {
            String refererOrigin = originFromReferer(referer);
            return refererOrigin != null && allowedOrigins.contains(refererOrigin);
        }

        return true;
    }

    private String originFromReferer(String referer) {
        try {
            URI uri = new URI(referer);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String origin = uri.getScheme() + "://" + uri.getHost();
            if (uri.getPort() != -1) {
                origin += ":" + uri.getPort();
            }
            return origin;
        } catch (URISyntaxException ex) {
            return null;
        }
    }
}
