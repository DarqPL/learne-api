package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTests {
    private final ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "2001:db8:ffff::/48"));

    private MockHttpServletRequest request(String peer, String... headers) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        for (String header : headers) request.addHeader("X-Forwarded-For", header);
        return request;
    }

    @Test
    void defaultAndUntrustedPeersIgnoreAllForwardingHeaders() {
        var request = request("192.0.2.1", "198.51.100.1");
        request.addHeader("CF-Connecting-IP", "203.0.113.1");
        request.addHeader("X-Real-IP", "203.0.113.2");
        request.addHeader("Forwarded", "for=203.0.113.3");
        assertThat(resolver.resolve(request)).isEqualTo("192.0.2.1");
        assertThat(new ClientIpResolver(List.of()).resolve(request("10.0.0.1", "198.51.100.1"))).isEqualTo("10.0.0.1");
    }

    @Test
    void walksFromRightAndNeverBlindlyTrustsSpoofedLeftmost() {
        assertThat(resolver.resolve(request("10.0.0.1", "203.0.113.99, 198.51.100.1, 10.1.2.3"))).isEqualTo("198.51.100.1");
        assertThat(resolver.resolve(request("10.0.0.1", "198.51.100.1", "10.1.2.3, 10.2.3.4"))).isEqualTo("198.51.100.1");
        assertThat(resolver.resolve(request("::ffff:10.0.0.1", " ::FFFF:192.0.2.1 , 10.1.1.1 "))).isEqualTo("192.0.2.1");
        assertThat(resolver.resolve(request("2001:db8:ffff::1", "2001:0DB8:0:0:0:0:0:1"))).isEqualTo("2001:db8::1");
    }

    @Test
    void compatibleHopCannotBeSkippedByIpv4ProxyTrust() {
        assertThat(resolver.resolve(request("10.0.0.1", "203.0.113.99, ::10.1.2.3")))
                .isEqualTo("::a01:203");
        assertThat(resolver.resolve(request("::10.1.2.3", "203.0.113.99"))).isEqualTo("::a01:203");
    }

    @Test
    void compatibleProxyCidrDoesNotTrustAnIpv4SocketPeer() {
        var compatible = new ClientIpResolver(List.of("::192.0.2.1/128"));
        assertThat(compatible.resolve(request("192.0.2.1", "203.0.113.99"))).isEqualTo("192.0.2.1");
        assertThat(compatible.resolve(request("::c000:201", "203.0.113.99"))).isEqualTo("203.0.113.99");
    }

    @Test
    void missingAllTrustedAndOtherHeadersFallBackWithFiniteReasons() {
        var reasons = new ArrayList<ClientIpResolver.FallbackReason>();
        var observed = new ClientIpResolver(List.of("10.0.0.0/8"), reasons::add);
        var missing = request("10.0.0.1");
        missing.addHeader("CF-Connecting-IP", "192.0.2.1");
        missing.addHeader("X-Real-IP", "192.0.2.1");
        missing.addHeader("Forwarded", "for=192.0.2.1");
        assertThat(observed.resolve(missing)).isEqualTo("10.0.0.1");
        assertThat(observed.resolve(request("10.0.0.1", "10.1.1.1"))).isEqualTo("10.0.0.1");
        assertThat(reasons).containsExactly(ClientIpResolver.FallbackReason.MISSING_HEADER, ClientIpResolver.FallbackReason.ALL_TRUSTED);
    }

    @Test
    void malformedHopAnywhereRejectsWholeChain() {
        for (String header : List.of("", " ", ",192.0.2.1", "192.0.2.1,", "192.0.2.1,,10.1.1.1",
                "localhost,192.0.2.1", "[::1]", "fe80::1%1", "192.0.2.1:80", "é,192.0.2.1", "192.0.2.1\n")) {
            assertThat(resolver.resolve(request("10.0.0.1", header))).as(header).isEqualTo("10.0.0.1");
        }
    }

    @Test
    void enforcesSixteenHopsAcrossHeaderOccurrences() {
        String sixteen = "192.0.2.1," + "10.1.1.1,".repeat(14) + "10.1.1.1";
        assertThat(resolver.resolve(request("10.0.0.1", sixteen))).isEqualTo("192.0.2.1");
        assertThat(resolver.resolve(request("10.0.0.1", sixteen, "10.1.1.1"))).isEqualTo("10.0.0.1");
    }

    @Test
    void enforces2048AsciiBytesAcrossAllHeaderValues() {
        String exact = "192.0.2.1" + " ".repeat(2048 - "192.0.2.1".length());
        assertThat(resolver.resolve(request("10.0.0.1", exact))).isEqualTo("192.0.2.1");
        assertThat(resolver.resolve(request("10.0.0.1", exact + " "))).isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", exact, "10.1.1.1"))).isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", "192.0.2.1\u00a0"))).isEqualTo("10.0.0.1");
    }

    @Test
    void hookReceivesOnlyFiniteReasonsForRejectedHeaders() {
        var reasons = new ArrayList<ClientIpResolver.FallbackReason>();
        var observed = new ClientIpResolver(List.of("10.0.0.0/8"), reasons::add);
        assertThat(observed.resolve(request("10.0.0.1", "private-hostname"))).isEqualTo("10.0.0.1");
        assertThat(observed.resolve(request("10.0.0.1", " ".repeat(2049)))).isEqualTo("10.0.0.1");
        assertThat(observed.resolve(request("10.0.0.1", "10.1.1.1,".repeat(16) + "10.1.1.1"))).isEqualTo("10.0.0.1");
        assertThat(reasons).containsExactly(ClientIpResolver.FallbackReason.MALFORMED_HEADER,
                ClientIpResolver.FallbackReason.HEADER_TOO_LARGE, ClientIpResolver.FallbackReason.TOO_MANY_HOPS);
    }

    @Test
    void invalidPeerAndNullRequestFailClosedWithoutSensitiveErrors() {
        for (String peer : new String[] {null, "", "private-hostname", "192.0.2.1:80", "fe80::1%eth0"}) {
            assertThatThrownBy(() -> resolver.resolve(request(peer, "192.0.2.1")))
                    .isInstanceOf(RateLimitException.class).hasMessage("Rate limiting unavailable");
        }
        assertThatThrownBy(() -> resolver.resolve(null)).isInstanceOf(RateLimitException.class).hasMessage("Rate limiting unavailable");
        assertThatIllegalArgumentException().isThrownBy(() -> new ClientIpResolver(List.of("bad-private-cidr")))
                .withMessage("Invalid trusted proxy CIDR");
        assertThatIllegalArgumentException().isThrownBy(() -> new ClientIpResolver(null));
    }
}
