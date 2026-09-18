package com.learne.backend.auth.session.security;

import com.learne.backend.config.CorsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TrustedOriginFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of("/api/auth/refresh", "/api/auth/logout");

    private final Set<String> allowedOrigins;

    public TrustedOriginFilter(CorsProperties corsProperties) {
        this.allowedOrigins = corsProperties.allowedOrigins().stream()
                .map(this::normalizeOrigin)
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
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
        if (origin != null) {
            return normalizeOrigin(origin)
                    .map(allowedOrigins::contains)
                    .orElse(false);
        }

        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer != null) {
            return normalizeOrigin(referer)
                    .map(allowedOrigins::contains)
                    .orElse(false);
        }

        return true;
    }

    private Optional<String> normalizeOrigin(String value) {
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return Optional.empty();
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String origin = scheme + "://" + host;
            if (uri.getPort() != -1 && !isDefaultPort(scheme, uri.getPort())) {
                origin += ":" + uri.getPort();
            }
            return Optional.of(origin);
        } catch (URISyntaxException ex) {
            return Optional.empty();
        }
    }

    private boolean isDefaultPort(String scheme, int port) {
        return ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
    }
}
