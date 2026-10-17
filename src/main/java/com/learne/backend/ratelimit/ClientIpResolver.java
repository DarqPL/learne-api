package com.learne.backend.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ClientIpResolver {
    public enum FallbackReason { MISSING_HEADER, MALFORMED_HEADER, HEADER_TOO_LARGE, TOO_MANY_HOPS, ALL_TRUSTED }
    private final List<TrustedProxyRange> trustedProxies;
    private final Consumer<FallbackReason> observer;

    public ClientIpResolver(List<String> trustedProxyCIDRs) {
        this(trustedProxyCIDRs, reason -> { });
    }

    public ClientIpResolver(List<String> trustedProxyCIDRs, Consumer<FallbackReason> observer) {
        if (trustedProxyCIDRs == null || observer == null) throw new IllegalArgumentException("Invalid client IP resolver configuration");
        this.trustedProxies = trustedProxyCIDRs.stream().map(TrustedProxyRange::parse).toList();
        this.observer = observer;
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) throw RateLimitException.unavailable();
        IpLiteral peer;
        try {
            peer = IpLiteral.parse(request.getRemoteAddr());
        } catch (IllegalArgumentException exception) {
            throw RateLimitException.unavailable();
        }
        if (!isTrusted(peer)) return peer.canonical();

        var headers = request.getHeaders("X-Forwarded-For");
        if (headers == null || !headers.hasMoreElements()) return fallback(peer, FallbackReason.MISSING_HEADER);
        int bytes = 0;
        var hops = new ArrayList<IpLiteral>();
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            if (header == null) return fallback(peer, FallbackReason.MALFORMED_HEADER);
            if (header.length() > 2048 - bytes) return fallback(peer, FallbackReason.HEADER_TOO_LARGE);
            bytes += header.length();
            for (int i = 0; i < header.length(); i++) {
                char c = header.charAt(i);
                if (c > 127 || (c < 32 && c != '\t') || c == 127) return fallback(peer, FallbackReason.MALFORMED_HEADER);
            }
            // Keep empty tokens so malformed chains cannot silently lose a hop.
            for (String hop : header.split(",", -1)) {
                if (hops.size() == 16) return fallback(peer, FallbackReason.TOO_MANY_HOPS);
                try {
                    hops.add(IpLiteral.parse(hop.trim()));
                } catch (IllegalArgumentException exception) {
                    return fallback(peer, FallbackReason.MALFORMED_HEADER);
                }
            }
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            IpLiteral hop = hops.get(i);
            if (!isTrusted(hop)) return hop.canonical();
        }
        return fallback(peer, FallbackReason.ALL_TRUSTED);
    }

    private boolean isTrusted(IpLiteral address) {
        return trustedProxies.stream().anyMatch(range -> range.contains(address));
    }

    private String fallback(IpLiteral peer, FallbackReason reason) {
        observer.accept(reason);
        return peer.canonical();
    }
}
