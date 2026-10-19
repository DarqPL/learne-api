package com.learne.backend.teacher.content.enrichment;

import java.util.Set;

/** Refresh-time source of persisted active contracts. No database source is installed until C3. */
@FunctionalInterface
public interface EnrichmentContractSource {
    Set<EnrichmentContract> activeContracts();
}
