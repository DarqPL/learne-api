package com.learne.backend.teacher.content.enrichment;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EnrichmentContractTests {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "01", "v1", "1.0", "1000000000", "١", "１", " 1", "1 ", "1\t", "1\r\n"})
    void rejectsNonCanonicalPromptVersionWithoutReflectingInput(String version) {
        assertThatThrownBy(() -> new EnrichmentContract("local-policy.v1", version, "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid enrichment contract version").hasNoCause();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "01", "v1", "1.0", "1000000000", "١", "１", " 1", "1 ", "1\t", "1\r\n"})
    void rejectsNonCanonicalResponseVersionWithoutReflectingInput(String version) {
        assertThatThrownBy(() -> new EnrichmentContract("local-policy.v1", "1", version))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid enrichment contract version").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "999999999"})
    void acceptsWireBoundsAndPreservesLocalPolicyIdentifiers(String version) {
        var contract = new EnrichmentContract("local-policy.v1", version, version);
        assertThat(contract.policyVersion()).isEqualTo("local-policy.v1");
        assertThat(contract.promptVersion()).isEqualTo(version);
        assertThat(contract.responseVersion()).isEqualTo(version);
        assertThat(new EnrichmentCapabilities.Tuple(contract.promptVersion(), contract.responseVersion()))
                .isEqualTo(new EnrichmentCapabilities.Tuple(version, version));
    }

    @Test void v1IsExplicitAndCanonical() {
        assertThat(EnrichmentContract.V1).isEqualTo(new EnrichmentContract("1","1","1"));
        for (String invalid : new String[]{"", " 1", "1 ", "1\r\nx", "x".repeat(65), "é"}) {
            assertThatThrownBy(() -> new EnrichmentContract(invalid,"1","1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new EnrichmentContract("1",invalid,"1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new EnrichmentContract("1","1",invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new EnrichmentContract(null,"1","1")).isInstanceOf(IllegalArgumentException.class);
    }
}
