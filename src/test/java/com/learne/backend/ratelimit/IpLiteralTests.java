package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IpLiteralTests {
    @ParameterizedTest
    @CsvSource({"192.0.2.1,192.0.2.1", "2001:0DB8:0000:0000:0000:0000:0000:0001,2001:db8::1",
            "::FFFF:192.0.2.1,192.0.2.1", "::ffff:c000:201,192.0.2.1", "0:0:0:0:0:0:0:0,::",
            "2001:0:0:1:0:0:1:1,2001::1:0:0:1:1", "::1,::1"})
    void canonicalizesLiteral(String input, String expected) {
        assertThat(IpLiteral.parse(input).canonical()).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"localhost", "example.com", "127.1", "192.0.2.1:80", "[::1]", "[::1]:80",
            "fe80::1%eth0", "fe80::1%1", " 192.0.2.1", "192.0.2.1 ", "256.1.1.1", ":::1", "https://[::1]"})
    void rejectsNonLiteralWithoutEchoingInput(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> IpLiteral.parse(input))
                .withMessage("Invalid IP literal");
    }

    @Test
    void compatibleDottedIpv6PreservesHexEquivalentRatherThanBecomingMapped() {
        assertThat(IpLiteral.parse("::192.0.2.1").canonical()).isEqualTo("::c000:201")
                .isEqualTo(IpLiteral.parse("::c000:201").canonical());
        assertThat(IpLiteral.parse("0:0:0:0:0:0:192.0.2.1").canonical()).isEqualTo("::c000:201");
        assertThat(IpLiteral.parse("::0.0.0.1").canonical()).isEqualTo("::1");
        assertThat(IpLiteral.parse("::ffff:192.0.2.1").canonical()).isEqualTo("192.0.2.1");
    }

    @Test
    void compatibleDottedCidrTrustsOnlyItsTrueIpv6Address() {
        var compatible = TrustedProxyRange.parse("::192.0.2.1/128");
        assertThat(compatible.contains(IpLiteral.parse("::c000:201"))).isTrue();
        assertThat(compatible.contains(IpLiteral.parse("192.0.2.1"))).isFalse();
        assertThat(compatible.contains(IpLiteral.parse("::ffff:192.0.2.1"))).isFalse();
        var ipv4 = TrustedProxyRange.parse("192.0.2.1/32");
        assertThat(ipv4.contains(IpLiteral.parse("::192.0.2.1"))).isFalse();
        assertThat(TrustedProxyRange.parse("::c000:201/128").contains(IpLiteral.parse("::192.0.2.1"))).isTrue();
    }

    @Test
    void rangesMaskHostBitsAndNormalizeMappedAddresses() {
        var ipv4 = TrustedProxyRange.parse("192.0.2.129/24");
        assertThat(ipv4.contains(IpLiteral.parse("::ffff:192.0.2.1"))).isTrue();
        assertThat(ipv4.contains(IpLiteral.parse("192.0.3.1"))).isFalse();
        assertThat(ipv4.contains(IpLiteral.parse("2001:db8::1"))).isFalse();
        var ipv6 = TrustedProxyRange.parse("2001:db8::ffff/33");
        assertThat(ipv6.contains(IpLiteral.parse("2001:db8:7fff::1"))).isTrue();
        assertThat(ipv6.contains(IpLiteral.parse("2001:db8:8000::1"))).isFalse();
        assertThat(TrustedProxyRange.parse("::ffff:192.0.2.129/120").contains(IpLiteral.parse("192.0.2.1"))).isTrue();
        assertThat(TrustedProxyRange.parse("192.0.2.1/32").contains(IpLiteral.parse("192.0.2.2"))).isFalse();
        assertThat(TrustedProxyRange.parse("::1/128").contains(IpLiteral.parse("::2"))).isFalse();
        assertThat(TrustedProxyRange.parse("0.0.0.0/0").isTrustAll()).isTrue();
        assertThat(TrustedProxyRange.parse("::/0").isTrustAll()).isTrue();
    }

    @Test
    void mappedCidrsSupportTheFullIpv6PrefixRange() {
        var broad = TrustedProxyRange.parse("::ffff:192.0.2.1/95");
        assertThat(broad.contains(IpLiteral.parse("198.51.100.1"))).isTrue();
        assertThat(broad.contains(IpLiteral.parse("::fffe:0:1"))).isTrue();
        assertThat(broad.contains(IpLiteral.parse("2001:db8::1"))).isFalse();
        assertThat(TrustedProxyRange.parse("::ffff:192.0.2.1/0").isTrustAll()).isTrue();
        assertThat(TrustedProxyRange.parse("::/0").contains(IpLiteral.parse("192.0.2.1"))).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"localhost/8", "192.0.2.1", "192.0.2.1/-1", "192.0.2.1/33", "::1/129",
            "::1/+1", "::1/01", "::1/", "::1/1/2", "[::1]/128"})
    void rejectsInvalidCidr(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> TrustedProxyRange.parse(input))
                .withMessage("Invalid trusted proxy CIDR");
    }
}
