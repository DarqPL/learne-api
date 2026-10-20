package com.learne.backend.teacher.content.submission;

import com.learne.backend.teacher.content.enrichment.EnrichmentContract;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SubmissionFingerprintTests {
    @Test void everyPayloadLimitTrimsAndBoundsAtTheExactBoundary() {
        // course/topic names and lesson title share 255; all descriptions share 1000.
        for(int limit:new int[]{255,1000,255,1000,255,1000,2000,1000,500,250,32}) {
            assertThat(SubmissionProjectionReader.bounded("x".repeat(limit-1),limit)).hasSize(limit-1);
            assertThat(SubmissionProjectionReader.bounded("x".repeat(limit),limit)).hasSize(limit);
            assertThat(SubmissionProjectionReader.bounded("  "+"x".repeat(limit+1)+"  ",limit)).isEqualTo("x".repeat(limit));
        }
        assertThat(SubmissionProjectionReader.bounded(null,255)).isEmpty();
    }
    private String hash(Object... values) {
        var hash = new SubmissionFingerprint(EnrichmentContract.V1, "test");
        for (Object value : values) hash.add(value);
        return hash.finish();
    }
    @Test void codecDistinguishesNullEmptyTypesAndEmbeddedSeparators() {
        assertThat(hash((Object) null)).isNotEqualTo(hash(""));
        assertThat(hash("1")).isNotEqualTo(hash(1));
        assertThat(hash("a\u001fb", "c")).isNotEqualTo(hash("a", "b\u001fc"));
        assertThat(hash("a\u001eb", "c")).isNotEqualTo(hash("a", "b\u001ec"));
    }
    @Test void unicodeAndNumericJdbcWidthsAreStable() {
        assertThat(hash("Tiếng Việt 日本語 🌱", 1)).isEqualTo(hash("Tiếng Việt 日本語 🌱", 1L));
        assertThat(hash("Tiếng Việt 日本語 🌱")).matches("[a-f0-9]{64}");
    }
    @Test void pinsContractAndRejectsUnknownLocalPolicy() {
        assertThatThrownBy(() -> new SubmissionFingerprint(new EnrichmentContract("2","1","1"), "test"))
                .isInstanceOf(IllegalArgumentException.class);
        var other = new SubmissionFingerprint(new EnrichmentContract("1","2","1"), "test");
        assertThat(other.finish()).isNotEqualTo(hash());
    }
}
