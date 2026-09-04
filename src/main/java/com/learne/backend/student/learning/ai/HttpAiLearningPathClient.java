package com.learne.backend.student.learning.ai;

import com.learne.backend.shared.api.ApiException;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpAiLearningPathClient implements AiLearningPathClient {
    private final AiServiceProperties properties;
    private final RestClient restClient;

    public HttpAiLearningPathClient(AiServiceProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(properties.timeoutMs());
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public AiLearningPathResponse generateLearningPath(AiLearningPathRequest request) {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI service is disabled.");
        }

        try {
            return restClient.post()
                    .uri("/learning-path/generate")
                    .body(request)
                    .retrieve()
                    .body(AiLearningPathResponse.class);
        } catch (RestClientException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI service is unavailable.");
        }
    }

    @Override
    public AiCourseRecommendationResponse generateCourseRecommendations(AiCourseRecommendationRequest request) {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI service is disabled.");
        }

        try {
            return restClient.post()
                    .uri("/course-recommendations/generate")
                    .body(request)
                    .retrieve()
                    .body(AiCourseRecommendationResponse.class);
        } catch (RestClientException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI service is unavailable.");
        }
    }
}
