package com.learne.backend.ai.tutor.ai;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.ai.AiServiceProperties;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpAiTutorClient implements AiTutorClient {
    private static final String UNAVAILABLE_MESSAGE = "AI tutor is temporarily unavailable. Please try again later.";

    private final AiServiceProperties properties;
    private final RestClient restClient;

    public HttpAiTutorClient(AiServiceProperties properties, RestClient.Builder restClientBuilder) {
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
    public AiTutorResponse generateReply(AiTutorRequest request) {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI service is disabled.");
        }

        try {
            AiTutorResponse response = restClient.post()
                    .uri("/ai-tutor/respond")
                    .body(request)
                    .retrieve()
                    .body(AiTutorResponse.class);
            if (response == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE_MESSAGE);
            }
            return response;
        } catch (RestClientException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE_MESSAGE);
        }
    }
}
