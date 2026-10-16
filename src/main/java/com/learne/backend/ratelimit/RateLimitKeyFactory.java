package com.learne.backend.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class RateLimitKeyFactory {
    private final RateLimitProperties properties;

    public RateLimitKeyFactory(RateLimitProperties properties) {
        if (properties == null) throw RateLimitException.unavailable();
        this.properties = properties;
    }

    public String key(String policyId, RateLimitPolicy policy, RateLimitDimension dimension, String identifier) {
        if (policyId == null || !policyId.matches("[a-z][a-z0-9-]{0,47}") || policy == null
                || dimension == null || policy.dimension() != dimension || identifier == null) {
            throw RateLimitException.unavailable();
        }
        try {
            String canonical = canonicalIdentifier(dimension, identifier);
            // A fresh Mac per call avoids mutable shared crypto state; disabled blank secrets stay lazy.
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] message = ("rl-identifier-v1\0" + dimension.wire() + '\0' + canonical).getBytes(StandardCharsets.UTF_8);
            String digest = HexFormat.of().formatHex(mac.doFinal(message));
            return properties.namespace() + ":" + properties.environment() + ":rl:v1:" + policyId + ":"
                    + policy.revision() + ":" + dimension.wire() + ":" + digest;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw RateLimitException.unavailable();
        }
    }

    private static String canonicalIdentifier(RateLimitDimension dimension, String identifier) {
        return switch (dimension) {
            case IP -> IpLiteral.parse(identifier).canonical();
            case EMAIL -> {
                if (identifier.indexOf('\0') >= 0) throw RateLimitException.unavailable();
                String email = identifier.trim().toLowerCase(Locale.ROOT);
                if (email.isEmpty() || email.getBytes(StandardCharsets.UTF_8).length > 254) {
                    throw RateLimitException.unavailable();
                }
                yield email;
            }
            case USER -> {
                if (!identifier.matches("[0-9]+")) throw RateLimitException.unavailable();
                long user = Long.parseLong(identifier);
                if (user <= 0) throw RateLimitException.unavailable();
                yield Long.toString(user);
            }
        };
    }
}
