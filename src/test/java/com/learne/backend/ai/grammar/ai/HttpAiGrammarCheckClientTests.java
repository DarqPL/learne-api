package com.learne.backend.ai.grammar.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.ai.AiServiceProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class HttpAiGrammarCheckClientTests {
    @Test
    void checkGrammarPostsRequestAndReturnsResponse() {
        AtomicBoolean called = new AtomicBoolean(false);
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            called.set(true);
            assertEquals(HttpMethod.POST, request.getMethod());
            assertEquals("http://ai-service:5000/grammar-checks/check", request.getURI().toString());
            assertEquals(MediaType.APPLICATION_JSON, request.getHeaders().getContentType());
            assertNull(request.getHeaders().getFirst("X-Internal-Service-Token"));
            assertEquals("{\"inputText\":\"I want order pizza.\",\"constraints\":{\"maxErrors\":20,\"allowedErrorTypes\":[\"GRAMMAR\",\"SPELLING\",\"PUNCTUATION\",\"WORD_CHOICE\",\"STYLE\",\"OTHER\"],\"positionBase\":\"ZERO_BASED_END_EXCLUSIVE\"}}",
                    new String(body, StandardCharsets.UTF_8));
            return jsonResponse("{\"errors\":[{\"errorText\":\"want order\",\"errorType\":\"GRAMMAR\",\"suggestion\":\"want to order\",\"explanation\":\"Use to.\",\"startPos\":2,\"endPos\":12}]}", HttpStatus.OK);
        });
        AiGrammarCheckClient client = new HttpAiGrammarCheckClient(
                new AiServiceProperties("http://ai-service:5000", 8000, true, ""), builder);

        AiGrammarCheckResponse response = client.checkGrammar(AiGrammarCheckRequest.create("I want order pizza."));

        assertEquals(true, called.get());
        assertEquals(1, response.errors().size());
        assertEquals("GRAMMAR", response.errors().getFirst().errorType());
    }

    @Test
    void checkGrammarSendsInternalServiceTokenWhenConfigured() {
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            assertEquals("secret-token", request.getHeaders().getFirst("X-Internal-Service-Token"));
            return jsonResponse("{\"errors\":[]}", HttpStatus.OK);
        });
        AiGrammarCheckClient client = new HttpAiGrammarCheckClient(
                new AiServiceProperties("http://ai-service:5000", 8000, true, "secret-token"), builder);

        client.checkGrammar(AiGrammarCheckRequest.create("Hello."));
    }

    @Test
    void checkGrammarThrowsServiceUnavailableWhenDisabled() {
        AiGrammarCheckClient client = new HttpAiGrammarCheckClient(
                new AiServiceProperties("http://ai-service:5000", 8000, false, ""), RestClient.builder());

        ApiException exception = assertThrows(ApiException.class,
                () -> client.checkGrammar(AiGrammarCheckRequest.create("Hello.")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("AI service is disabled.", exception.getMessage());
    }

    @Test
    void checkGrammarWrapsRestClientFailuresAsBadGateway() {
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            throw new RestClientException("boom");
        });
        AiGrammarCheckClient client = new HttpAiGrammarCheckClient(
                new AiServiceProperties("http://ai-service:5000", 8000, true, ""), builder);

        ApiException exception = assertThrows(ApiException.class,
                () -> client.checkGrammar(AiGrammarCheckRequest.create("Hello.")));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        assertEquals("AI grammar check is temporarily unavailable. Please try again later.", exception.getMessage());
    }

    private static MockClientHttpResponse jsonResponse(String body, HttpStatus status) throws IOException {
        MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return response;
    }
}
