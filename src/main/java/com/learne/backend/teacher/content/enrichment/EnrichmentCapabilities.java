package com.learne.backend.teacher.content.enrichment;

import com.learne.backend.student.learning.ai.AiServiceProperties;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fetches exact execution tuples through the owned, five-second bounded transport. */
public final class EnrichmentCapabilities {
    private final AiServiceProperties properties;
    private final EnrichmentHttpTransport transport;
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxStringLength(64).maxNumberLength(9).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public EnrichmentCapabilities(AiServiceProperties properties, EnrichmentHttpTransport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    public record Tuple(String promptVersion, String responseVersion) {
        public Tuple {
            canonical(promptVersion);
            canonical(responseVersion);
        }
        private static void canonical(String version) {
            if (version == null || !version.matches("[1-9][0-9]{0,8}")) throw invalid();
        }
    }

    public Set<Tuple> fetch() {
        try {
            URI uri = URI.create(properties.baseUrl().replaceAll("/+$", "") + "/internal/capabilities/course-enrichment");
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) throw invalid();
            var response = transport.capabilities(uri, properties.internalToken());
            if (response.status() != 200) throw invalid();
            JsonNode root = mapper.readTree(response.body());
            if (root == null || !root.isObject() || !root.path("supportedContracts").isArray()) throw invalid();
            var tuples = new HashSet<Tuple>();
            for (JsonNode item : root.path("supportedContracts")) {
                if (!item.isObject() || !item.path("promptVersion").isTextual()
                        || !item.path("responseVersion").isTextual()) throw invalid();
                var tuple = new Tuple(item.path("promptVersion").textValue(), item.path("responseVersion").textValue());
                if (!tuples.add(tuple)) throw invalid();
            }
            return Set.copyOf(tuples);
        } catch (RuntimeException ignored) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Enrichment capabilities unavailable");
    }
}
