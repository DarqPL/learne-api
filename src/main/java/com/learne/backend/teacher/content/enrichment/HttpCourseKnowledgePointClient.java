package com.learne.backend.teacher.content.enrichment;

import com.learne.backend.jobs.service.*;
import com.learne.backend.student.learning.ai.AiServiceProperties;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HttpCourseKnowledgePointClient implements CourseKnowledgePointClient {
    private static final String PROMPT = "X-Enrichment-Prompt-Version";
    private static final String RESPONSE = "X-Enrichment-Response-Version";
    private final AiServiceProperties properties;
    private final EnrichmentHttpTransport transport;
    private final ObjectMapper mapper = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(EnrichmentHttpTransport.MAX_BYTES).maxNumberLength(32).build()).build()).build();

    public HttpCourseKnowledgePointClient(AiServiceProperties properties, EnrichmentHttpTransport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    @Override public CourseKnowledgePointResponse enrichKnowledgePoints(CourseKnowledgePointRequest request, EnrichmentContract contract) {
        if (!properties.enabled()) throw new PermanentJobException("ENRICHMENT_DISABLED");
        if (contract == null || !EnrichmentResponseSupport.supports(contract.responseVersion()))
            throw new PermanentJobException("ENRICHMENT_CONTRACT_UNAVAILABLE");
        byte[] body;
        try { body = mapper.writeValueAsBytes(request); }
        catch (RuntimeException error) { throw new PermanentJobException("ENRICHMENT_INVALID_REQUEST"); }
        if (body.length > EnrichmentHttpTransport.MAX_BYTES) throw new PermanentJobException("ENRICHMENT_INVALID_REQUEST");
        HttpRequest outgoing;
        try {
            String base = properties.baseUrl().replaceAll("/+$", "");
            URI uri = URI.create(base + "/course-content/enrich-knowledge-points");
            if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                    .header(PROMPT, contract.promptVersion()).header(RESPONSE, contract.responseVersion())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (!properties.internalToken().isBlank()) builder.header("X-Internal-Service-Token", properties.internalToken());
            outgoing = builder.build();
        } catch (IllegalArgumentException error) { throw new PermanentJobException("ENRICHMENT_CONFIGURATION_UNAVAILABLE"); }
        EnrichmentHttpTransport.Response result = transport.exchange(outgoing, transport.requestTimeout());
        if (result.status() < 200 || result.status() >= 300) throw classify(result);
        if (!result.headers().allValues(PROMPT).equals(List.of(contract.promptVersion()))
                || !result.headers().allValues(RESPONSE).equals(List.of(contract.responseVersion())))
            throw new PermanentJobException("ENRICHMENT_CONTRACT_UNAVAILABLE");
        try {
            JsonNode root = mapper.readTree(result.body());
            if (root == null || !root.isObject() || !root.path("items").isArray() || root.path("items").size() > 25) throw invalid();
            List<CourseKnowledgePointResponse.Item> items = new ArrayList<>();
            for (JsonNode item : root.path("items")) {
                JsonNode id = item.path("questionId");
                JsonNode point = item.path("knowledgePoint");
                if (!item.isObject() || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0 || !point.isTextual()) throw invalid();
                items.add(new CourseKnowledgePointResponse.Item(id.longValue(), point.textValue()));
            }
            return new CourseKnowledgePointResponse(List.copyOf(items));
        } catch (RuntimeException error) { throw invalid(); }
    }

    private RuntimeException classify(EnrichmentHttpTransport.Response response) {
        String code = "";
        try { code = mapper.readTree(response.body()).path("code").asString(""); }
        catch (RuntimeException ignored) { }
        int status = response.status();
        if (status == 401 || status == 403) return new PermanentJobException("ENRICHMENT_AUTHENTICATION");
        if (status == 400 || status == 413) return new PermanentJobException("ENRICHMENT_INVALID_REQUEST");
        if (status == 409 && code.equals("ENRICHMENT_CONTRACT_UNSUPPORTED")) return new PermanentJobException("ENRICHMENT_CONTRACT_UNAVAILABLE");
        if (status == 503 && code.equals("ENRICHMENT_CONFIGURATION_UNAVAILABLE")) return new PermanentJobException("ENRICHMENT_CONFIGURATION_UNAVAILABLE");
        if (status == 502 && (code.equals("ENRICHMENT_DEADLINE_EXCEEDED") || code.equals("ENRICHMENT_GENERATION_FAILED")))
            return new RetryableJobException(code, retryAfter(response));
        if (status == 429) return new RetryableJobException("THROTTLED", retryAfter(response));
        if (status >= 500) return new RetryableJobException("ENRICHMENT_UNAVAILABLE", retryAfter(response));
        return new PermanentJobException("ENRICHMENT_CONFIGURATION_UNAVAILABLE");
    }

    private Duration retryAfter(EnrichmentHttpTransport.Response response) {
        var values = response.headers().allValues("Retry-After");
        if (values.size() != 1 || values.getFirst().length() > 128) return null;
        String value = values.getFirst();
        try {
            Duration delay = value.matches("[0-9]{1,10}") ? Duration.ofSeconds(Long.parseLong(value))
                    : Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return delay.isNegative() || delay.compareTo(Duration.ofDays(1)) > 0 ? null : delay;
        } catch (RuntimeException ignored) { return null; }
    }

    private RetryableJobException invalid() { return new RetryableJobException("ENRICHMENT_INVALID_RESPONSE", null); }
}
