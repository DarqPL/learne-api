package com.learne.backend.student.learning.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.learne.backend.shared.api.ApiException;
import java.io.IOException;
import java.math.BigDecimal;
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

class HttpAiLearningPathClientTests {
    @Test
    void generateLearningPathPostsRequestAndReturnsResponse() {
        AtomicBoolean called = new AtomicBoolean(false);
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            called.set(true);
            assertEquals(HttpMethod.POST, request.getMethod());
            assertEquals("http://ai-service:5000/learning-path/generate", request.getURI().toString());
            assertEquals(MediaType.APPLICATION_JSON, request.getHeaders().getContentType());
            assertEquals("{\"student\":{\"id\":7,\"level\":\"BEGINNER\"},\"constraints\":{\"maxItems\":2,\"allowedLessonIds\":[10,11]},\"learningHistory\":{\"recentQuizAttempts\":[],\"questionAnswers\":[]},\"candidateLessons\":[]}",
                    new String(body, StandardCharsets.UTF_8));
            return jsonResponse("{\"weaknesses\":[\"algebra\"],\"recommendations\":[{\"lessonId\":10,\"score\":0.91,\"reason\":\"Needs practice\"}]}",
                    HttpStatus.OK);
        });
        AiLearningPathClient client = new HttpAiLearningPathClient(
                new AiServiceProperties("http://ai-service:5000", 8000, true), builder);

        AiLearningPathRequest request = new AiLearningPathRequest(
                new AiLearningPathRequest.StudentPayload(7L, "BEGINNER"),
                new AiLearningPathRequest.ConstraintPayload(2, List.of(10L, 11L)),
                new AiLearningPathRequest.LearningHistoryPayload(List.of(), List.of()),
                List.of());

        AiLearningPathResponse response = client.generateLearningPath(request);

        assertEquals(true, called.get());
        assertEquals(List.of("algebra"), response.weaknesses());
        assertEquals(10L, response.recommendations().getFirst().lessonId());
        assertEquals(new BigDecimal("0.91"), response.recommendations().getFirst().score());
        assertEquals("Needs practice", response.recommendations().getFirst().reason());
    }

    @Test
    void generateCourseRecommendationsPostsToAiServiceCourseEndpoint() {
        AtomicBoolean called = new AtomicBoolean(false);
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            called.set(true);
            assertEquals(HttpMethod.POST, request.getMethod());
            assertEquals("http://ai-service:5000/course-recommendations/generate", request.getURI().toString());
            assertEquals(MediaType.APPLICATION_JSON, request.getHeaders().getContentType());
            assertEquals("{\"student\":{\"id\":7,\"level\":\"BEGINNER\"},\"assessmentSummary\":{\"assessmentId\":3,\"level\":\"BEGINNER\",\"scorePercent\":72,\"completedAt\":\"2026-09-01T10:15:30Z\"},\"skillStats\":[{\"skill\":\"grammar\",\"scorePercent\":58,\"attempts\":4,\"weakKnowledgePoints\":[\"present simple\"]}],\"learningHistory\":{\"recentCourseIds\":[1,2],\"recentLessonTitles\":[\"Present simple\"],\"weaknesses\":[\"present simple\"]},\"candidateCourses\":[{\"courseId\":10,\"title\":\"Grammar Basics\",\"name\":\"Grammar Basics\",\"type\":\"RECORDED\",\"level\":\"BEGINNER\",\"description\":\"Build grammar foundations\",\"teacherName\":\"Teacher Linh\",\"enrolled\":false}],\"constraints\":{\"maxItems\":3,\"allowedCourseIds\":[10,11]}}",
                    new String(body, StandardCharsets.UTF_8));
            return jsonResponse("{\"weaknesses\":[\"present simple\"],\"recommendations\":[{\"courseId\":10,\"score\":0.94,\"reason\":\"Grammar fit\"}]}",
                    HttpStatus.OK);
        });
        AiLearningPathClient client = new HttpAiLearningPathClient(
                new AiServiceProperties("http://ai-service:5000", 8000, true), builder);

        AiCourseRecommendationResponse response = client.generateCourseRecommendations(sampleCourseRequest());

        assertEquals(true, called.get());
        assertEquals(1, response.recommendations().size());
        assertEquals(10L, response.recommendations().getFirst().courseId());
    }

    @Test
    void generateLearningPathThrowsServiceUnavailableWhenDisabled() {
        HttpAiLearningPathClient client = new HttpAiLearningPathClient(
                new AiServiceProperties("http://ai-service:5000", 8000, false), RestClient.builder());

        ApiException exception = assertThrows(ApiException.class, () -> client.generateLearningPath(null));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("AI service is disabled.", exception.getMessage());
    }

    @Test
    void generateLearningPathWrapsRestClientFailuresAsServiceUnavailable() {
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            throw new RestClientException("boom");
        });
        HttpAiLearningPathClient client = new HttpAiLearningPathClient(
                new AiServiceProperties("http://ai-service:5000", 8000, true), builder);

        ApiException exception = assertThrows(ApiException.class, () -> client.generateLearningPath(new AiLearningPathRequest(
                new AiLearningPathRequest.StudentPayload(7L, "BEGINNER"),
                new AiLearningPathRequest.ConstraintPayload(2, List.of(10L)),
                new AiLearningPathRequest.LearningHistoryPayload(List.of(), List.of()),
                List.of())));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("AI service is unavailable.", exception.getMessage());
    }

    private static AiCourseRecommendationRequest sampleCourseRequest() {
        return new AiCourseRecommendationRequest(
                new AiCourseRecommendationRequest.StudentPayload(7L, "BEGINNER"),
                new AiCourseRecommendationRequest.AssessmentSummaryPayload(
                        3L, "BEGINNER", 72, "2026-09-01T10:15:30Z"),
                List.of(new AiCourseRecommendationRequest.SkillStatPayload("grammar", 58, 4, List.of("present simple"))),
                new AiCourseRecommendationRequest.LearningHistoryPayload(
                        List.of(1L, 2L), List.of("Present simple"), List.of("present simple")),
                List.of(new AiCourseRecommendationRequest.CandidateCoursePayload(
                        10L,
                        "Grammar Basics",
                        "Grammar Basics",
                        "RECORDED",
                        "BEGINNER",
                        "Build grammar foundations",
                        "Teacher Linh",
                        false)),
                new AiCourseRecommendationRequest.ConstraintPayload(3, List.of(10L, 11L)));
    }

    private static MockClientHttpResponse jsonResponse(String body, HttpStatus status) throws IOException {
        MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return response;
    }
}
