package com.learne.backend.ratelimit;

import io.netty.util.NetUtil;
import java.util.Arrays;

/** A validated, byte-backed literal. Parsing and rendering never perform DNS lookups. */
public final class IpLiteral {
    private final byte[] bytes;

    private IpLiteral(byte[] bytes) {
        this.bytes = bytes;
    }

    public static IpLiteral parse(String input) {
        // Netty also accepts bracketed and scoped IPv6; neither is an identifier here.
        if (input == null || input.indexOf('%') >= 0 || input.indexOf('[') >= 0 || input.indexOf(']') >= 0
                || !(NetUtil.isValidIpV4Address(input) || NetUtil.isValidIpV6Address(input))) {
            throw new IllegalArgumentException("Invalid IP literal");
        }
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(expandDottedIpv6Tail(input));
        if (bytes == null) throw new IllegalArgumentException("Invalid IP literal");
        if (isMapped(bytes)) bytes = Arrays.copyOfRange(bytes, 12, 16);
        return new IpLiteral(bytes);
    }

    private static String expandDottedIpv6Tail(String input) {
        if (input.indexOf(':') < 0 || input.indexOf('.') < 0) return input;
        int tailStart = input.lastIndexOf(':') + 1;
        String tail = input.substring(tailStart);
        if (!NetUtil.isValidIpV4Address(tail)) throw new IllegalArgumentException("Invalid IP literal");
        byte[] ipv4 = NetUtil.createByteArrayFromIpAddressString(tail);
        int high = ((ipv4[0] & 0xff) << 8) | (ipv4[1] & 0xff);
        int low = ((ipv4[2] & 0xff) << 8) | (ipv4[3] & 0xff);
        // Netty 4.2 promotes compatible dotted IPv6 to mapped bytes. Hex input preserves the actual prefix.
        String expanded = input.substring(0, tailStart) + Integer.toHexString(high) + ":" + Integer.toHexString(low);
        if (!NetUtil.isValidIpV6Address(expanded)) throw new IllegalArgumentException("Invalid IP literal");
        return expanded;
    }

    private static boolean isMapped(byte[] bytes) {
        if (bytes.length != 16 || bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) return false;
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        return true;
    }

    public String canonical() {
        return NetUtil.bytesToIpAddress(bytes);
    }

    // A defensive copy keeps range masking from mutating the identifier.
    byte[] addressBytes() {
        return bytes.clone();
    }
}
