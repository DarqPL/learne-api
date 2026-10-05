package com.learne.backend.shared.pagination;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.shared.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CursorTokenService {
    private static final int VERSION = 1;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final byte[] secret;

    @Autowired
    public CursorTokenService(
            @Value("${app.auth.jwt-secret:learne-development-pagination-secret-change-me-please-32-bytes}") String secret) {
        this.objectMapper = new ObjectMapper();
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String encode(
            String endpoint,
            String sort,
            Map<String, ?> filters,
            Map<String, ?> sortValues) {
        try {
            Map<String, Object> payload = Map.of(
                    "version", VERSION,
                    "endpoint", normalize(endpoint),
                    "sort", normalize(sort),
                    "filters", normalizeMap(filters),
                    "sortValues", normalizeMap(sortValues),
                    "issuedAt", Instant.now().toString());
            String encodedPayload = encodeBytes(objectMapper.writeValueAsBytes(payload));
            return encodedPayload + "." + encodeBytes(sign(encodedPayload));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode cursor.", exception);
        }
    }

    public DecodedCursor decode(
            String token,
            String expectedEndpoint,
            String expectedSort,
            Map<String, ?> expectedFilters) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 2) {
                throw invalidCursor();
            }
            byte[] expectedSignature = sign(parts[0]);
            byte[] actualSignature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
                throw invalidCursor();
            }

            Map<String, Object> payload = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(parts[0]),
                    new TypeReference<>() {
                    });
            if (!Integer.valueOf(VERSION).equals(payload.get("version"))
                    || !normalize(expectedEndpoint).equals(payload.get("endpoint"))
                    || !normalize(expectedSort).equals(payload.get("sort"))
                    || !normalizeMap(expectedFilters).equals(payload.get("filters"))) {
                throw invalidCursor();
            }

            return new DecodedCursor(
                    castStringMap(payload.get("sortValues")),
                    String.valueOf(payload.get("issuedAt")));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    public record DecodedCursor(Map<String, String> sortValues, String issuedAt) {
    }

    private byte[] sign(String value) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeBytes(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String normalize(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static Map<String, String> normalizeMap(Map<String, ?> values) {
        Map<String, String> normalized = new TreeMap<>();
        if (values != null) {
            values.forEach((key, value) -> normalized.put(normalize(key), normalize(value)));
        }
        return normalized;
    }

    private static Map<String, String> castStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalidCursor();
        }
        Map<String, String> result = new TreeMap<>();
        map.forEach((key, entryValue) -> result.put(normalize(key), normalize(entryValue)));
        return result;
    }

    private static ApiException invalidCursor() {
        return new ApiException(HttpStatus.BAD_REQUEST, "Invalid cursor.");
    }
}
