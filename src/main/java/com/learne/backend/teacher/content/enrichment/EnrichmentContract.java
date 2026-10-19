package com.learne.backend.teacher.content.enrichment;

/** Persisted policy pin and the exact AI execution tuple; never silently default a caller. */
public record EnrichmentContract(String policyVersion, String promptVersion, String responseVersion) {
    public static final EnrichmentContract V1 = new EnrichmentContract("1", "1", "1");

    public EnrichmentContract {
        requirePolicyIdentifier(policyVersion);
        requireWireVersion(promptVersion);
        requireWireVersion(responseVersion);
    }

    private static void requirePolicyIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid enrichment contract version");
        }
    }

    private static void requireWireVersion(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,8}")) {
            throw new IllegalArgumentException("Invalid enrichment contract version");
        }
    }
}
