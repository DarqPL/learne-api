package com.learne.backend.teacher.content.submission;

import com.learne.backend.teacher.content.enrichment.EnrichmentContract;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Streaming typed, byte-length-delimited codec. Integral JDBC widths share one canonical type. */
public final class SubmissionFingerprint {
    public static final String FORMAT = "submission-sha256-v1";
    private final MessageDigest digest;
    public SubmissionFingerprint(EnrichmentContract contract, String domain) {
        if (!"1".equals(contract.policyVersion())) throw new IllegalArgumentException("Unsupported local policy");
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
        add(FORMAT); add(domain); add(contract.policyVersion()); add(contract.promptVersion()); add(contract.responseVersion());
    }
    public void add(Object value) {
        if (value == null) { digest.update((byte) 0); return; }
        byte type;
        String text;
        if (value instanceof String s) { type = 1; text = s; }
        else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            type = 2; text = value.toString();
        } else if (value instanceof Boolean b) { type = 3; text = b ? "1" : "0"; }
        else throw new IllegalArgumentException("Unsupported fingerprint scalar type");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(type);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
    public String finish() { return HexFormat.of().formatHex(digest.digest()); }
}
