package com.learne.backend.teacher.content.enrichment;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.ai.AiServiceProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

@Component
public class HttpCourseKnowledgePointClient implements CourseKnowledgePointClient {
    private static final String UNAVAILABLE_MESSAGE =
            "Không thể tự động gắn điểm kiến thức. Vui lòng thử gửi duyệt lại sau.";

    private final AiServiceProperties properties;
    private final RestClient restClient;

    public HttpCourseKnowledgePointClient(AiServiceProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(properties.timeoutMs());
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .defaultHeaders(headers -> {
                    if (!properties.internalToken().isBlank()) {
                        headers.set("X-Internal-Service-Token", properties.internalToken());
                    }
                })
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public CourseKnowledgePointResponse enrichKnowledgePoints(CourseKnowledgePointRequest request) {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
        try {
            JsonNode response = restClient.post()
                    .uri("/course-content/enrich-knowledge-points")
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE_MESSAGE);
            }
            return parseResponse(response);
        } catch (RuntimeException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE_MESSAGE);
        }
    }

    private CourseKnowledgePointResponse parseResponse(JsonNode response) {
        JsonNode items = response.get("items");
        if (items == null || !items.isArray()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE_MESSAGE);
        }
        List<CourseKnowledgePointResponse.Item> parsedItems = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode questionId = item.get("questionId");
            JsonNode knowledgePoint = item.get("knowledgePoint");
            if (questionId == null || !questionId.isIntegralNumber()
                    || knowledgePoint == null || !knowledgePoint.isTextual()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE_MESSAGE);
            }
            parsedItems.add(new CourseKnowledgePointResponse.Item(questionId.longValue(), knowledgePoint.textValue()));
        }
        return new CourseKnowledgePointResponse(parsedItems);
    }
}
