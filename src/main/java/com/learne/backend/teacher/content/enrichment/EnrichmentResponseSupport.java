package com.learne.backend.teacher.content.enrichment;

import java.util.Set;

/** Response versions with validators implemented by this backend, independent of AI capabilities. */
final class EnrichmentResponseSupport {
    private static final Set<String> IMPLEMENTED = Set.of("1");

    private EnrichmentResponseSupport() { }

    static boolean supports(String responseVersion) {
        return IMPLEMENTED.contains(responseVersion);
    }
}
