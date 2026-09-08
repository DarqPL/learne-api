package com.learne.backend.ai.tutor.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.ai.AiServiceProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class HttpAiTutorClientTests {
    @Test
    void generateReplyPostsRequestAndReturnsResponse() {
        AtomicBoolean called = new AtomicBoolean(false);
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            called.set(true);
            assertEquals(HttpMethod.POST, request.getMethod());
            assertEquals("http://ai-service:5000/ai-tutor/respond", request.getURI().toString());
            assertEquals(MediaType.APPLICATION_JSON, request.getHeaders().getContentType());
            assertEquals("{\"practiceType\":\"ROLEPLAY\",\"title\":\"At the restaurant\",\"latestMessage\":\"I want order pizza.\",\"recentMessages\":[{\"senderType\":\"USER\",\"message\":\"Hello.\"}],\"constraints\":{\"maxReplyLength\":4000,\"maxFeedbackItems\":1,\"feedbackStyle\":\"GENTLE\"}}",
                    new String(body, StandardCharsets.UTF_8));
            return jsonResponse("{\"replyText\":\"Sure, what kind of pizza would you like?\",\"feedback\":{\"errorType\":\"GRAMMAR\",\"originalText\":\"I want order pizza.\",\"correctedText\":\"I want to order a pizza.\",\"explanation\":\"Use want to.\"}}", HttpStatus.OK);
        });
        AiTutorClient client = new HttpAiTutorClient(
                new AiServiceProperties("http://ai-service:5000", 8000, true), builder);

        AiTutorResponse response = client.generateReply(new AiTutorRequest(
                "ROLEPLAY",
                "At the restaurant",
                "I want order pizza.",
                List.of(new AiTutorRequest.MessagePayload("USER", "Hello.")),
                new AiTutorRequest.ConstraintPayload(4000, 1, "GENTLE")));

        assertEquals(true, called.get());
        assertEquals("Sure, what kind of pizza would you like?", response.replyText());
        assertEquals("GRAMMAR", response.feedback().errorType());
    }

    @Test
    void generateReplyThrowsServiceUnavailableWhenDisabled() {
        AiTutorClient client = new HttpAiTutorClient(
                new AiServiceProperties("http://ai-service:5000", 8000, false), RestClient.builder());

        ApiException exception = assertThrows(ApiException.class,
                () -> client.generateReply(AiTutorRequest.create("FREE_CHAT", "Free talk", "Hello.", List.of())));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("AI service is disabled.", exception.getMessage());
    }

    @Test
    void generateReplyWrapsRestClientFailuresAsBadGateway() {
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            throw new RestClientException("boom");
        });
        AiTutorClient client = new HttpAiTutorClient(
                new AiServiceProperties("http://ai-service:5000", 8000, true), builder);

        ApiException exception = assertThrows(ApiException.class,
                () -> client.generateReply(AiTutorRequest.create("FREE_CHAT", "Free talk", "Hello.", List.of())));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        assertEquals("AI tutor is temporarily unavailable. Please try again later.", exception.getMessage());
    }

    @Test
    void generateReplyWrapsEmptyBodyAsBadGateway() {
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) ->
                jsonResponse("", HttpStatus.OK));
        AiTutorClient client = new HttpAiTutorClient(
                new AiServiceProperties("http://ai-service:5000", 8000, true), builder);

        ApiException exception = assertThrows(ApiException.class,
                () -> client.generateReply(AiTutorRequest.create("FREE_CHAT", "Free talk", "Hello.", List.of())));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        assertEquals("AI tutor is temporarily unavailable. Please try again later.", exception.getMessage());
    }

    private static MockClientHttpResponse jsonResponse(String body, HttpStatus status) throws IOException {
        MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return response;
    }
}
