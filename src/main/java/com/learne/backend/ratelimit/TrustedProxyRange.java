package com.learne.backend.ratelimit;

/** Immutable masked CIDR; IPv4 candidates use mapped bytes when compared with IPv6 ranges. */
public final class TrustedProxyRange {
    private final byte[] network;
    private final int prefix;

    private TrustedProxyRange(byte[] network, int prefix) {
        this.network = network;
        this.prefix = prefix;
        for (int i = 0; i < network.length; i++) network[i] &= mask(i, prefix);
    }

    public static TrustedProxyRange parse(String input) {
        try {
            if (input == null) throw new IllegalArgumentException();
            String[] parts = input.split("/", -1);
            if (parts.length != 2 || !parts[1].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException();
            byte[] bytes = IpLiteral.parse(parts[0]).addressBytes();
            int prefix = Integer.parseInt(parts[1]);
            boolean ipv6Syntax = parts[0].indexOf(':') >= 0;
            int max = ipv6Syntax ? 128 : 32;
            if (prefix > max) throw new IllegalArgumentException();
            if (ipv6Syntax && bytes.length == 4) {
                // Retain IPv6 width for masking, including prefixes outside the mapped /96.
                bytes = mappedBytes(bytes);
            }
            return new TrustedProxyRange(bytes, prefix);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid trusted proxy CIDR");
        }
    }

    public boolean contains(IpLiteral address) {
        if (address == null) return false;
        byte[] candidate = address.addressBytes();
        if (network.length == 16 && candidate.length == 4) candidate = mappedBytes(candidate);
        if (candidate.length != network.length) return false;
        for (int i = 0; i < network.length; i++) {
            if ((byte) (candidate[i] & mask(i, prefix)) != network[i]) return false;
        }
        return true;
    }

    private static int mask(int index, int prefix) {
        int bits = Math.max(0, Math.min(8, prefix - index * 8));
        return (0xff << (8 - bits)) & 0xff;
    }

    private static byte[] mappedBytes(byte[] ipv4) {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        System.arraycopy(ipv4, 0, mapped, 12, 4);
        return mapped;
    }

    public boolean isTrustAll() {
        return prefix == 0;
    }
}
