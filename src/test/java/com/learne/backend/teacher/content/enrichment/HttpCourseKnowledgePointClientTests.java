package com.learne.backend.teacher.content.enrichment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.ai.AiServiceProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

class HttpCourseKnowledgePointClientTests {
    @Test
    void rejectsStringQuestionIdInAiResponse() {
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) ->
                jsonResponse("{\"items\":[{\"questionId\":\"101\",\"knowledgePoint\":\"dates\"}]}", HttpStatus.OK));
        HttpCourseKnowledgePointClient client = new HttpCourseKnowledgePointClient(
                new AiServiceProperties("http://ai-service:5000", 8000, true, ""), builder);

        ApiException exception = assertThrows(ApiException.class,
                () -> client.enrichKnowledgePoints(request()));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
    }

    @Test
    void rejectsNumericKnowledgePointInAiResponse() {
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) ->
                jsonResponse("{\"items\":[{\"questionId\":101,\"knowledgePoint\":123}]}", HttpStatus.OK));
        HttpCourseKnowledgePointClient client = new HttpCourseKnowledgePointClient(
                new AiServiceProperties("http://ai-service:5000", 8000, true, ""), builder);

        ApiException exception = assertThrows(ApiException.class,
                () -> client.enrichKnowledgePoints(request()));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
    }

    private CourseKnowledgePointRequest request() {
        return new CourseKnowledgePointRequest(
                new CourseKnowledgePointRequest.CoursePayload(1L, "Course", "", "COMPREHENSIVE", "A2"),
                List.of(new CourseKnowledgePointRequest.QuestionPayload(
                        101L, 10L, 1, "Topic", "", 20L, 1, "Lesson", "", "Content",
                        "READING", 1, "MULTIPLE_CHOICE", "Question", "", List.of())));
    }

    private static MockClientHttpResponse jsonResponse(String body, HttpStatus status) throws IOException {
        MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return response;
    }
}
