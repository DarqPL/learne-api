package com.learne.backend.teacher.content.enrichment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.learne.backend.jobs.service.*;
import com.learne.backend.student.learning.ai.AiServiceProperties;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class HttpCourseKnowledgePointClientTests {
    private final EnrichmentHttpTransport transport = mock(EnrichmentHttpTransport.class);
    private HttpCourseKnowledgePointClient client(boolean enabled) {
        when(transport.requestTimeout()).thenReturn(Duration.ofSeconds(120));
        return new HttpCourseKnowledgePointClient(new AiServiceProperties("http://localhost:5000/base", 8, enabled, "secret"), transport);
    }
    private EnrichmentHttpTransport.Response response(int status, String body, Map<String,List<String>> headers) {
        return new EnrichmentHttpTransport.Response(status, HttpHeaders.of(headers, (a,b) -> true), body.getBytes(StandardCharsets.UTF_8));
    }
    private Map<String,List<String>> echo() {
        return Map.of("X-Enrichment-Prompt-Version", List.of("1"), "X-Enrichment-Response-Version", List.of("1"));
    }
    private CourseKnowledgePointRequest request() {
        return new CourseKnowledgePointRequest(new CourseKnowledgePointRequest.CoursePayload(1L,"Course","","COMPREHENSIVE","A2"), List.of());
    }
    @Test void sendsPinnedHeadersAndUnchangedBody() {
        when(transport.exchange(any(), any())).thenReturn(response(200,"{\"items\":[{\"questionId\":101,\"knowledgePoint\":\"dates\"}]}", echo()));
        assertThat(client(true).enrichKnowledgePoints(request(), EnrichmentContract.V1).items()).hasSize(1);
        var capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).exchange(capture.capture(), eq(Duration.ofSeconds(120)));
        HttpRequest sent = capture.getValue();
        assertThat(sent.uri().toString()).isEqualTo("http://localhost:5000/base/course-content/enrich-knowledge-points");
        assertThat(sent.headers().firstValue("X-Enrichment-Prompt-Version")).contains("1");
        assertThat(sent.headers().firstValue("X-Enrichment-Response-Version")).contains("1");
        assertThat(sent.headers().firstValue("X-Internal-Service-Token")).contains("secret");
        assertThat(sent.bodyPublisher().orElseThrow().contentLength()).isPositive();
        var bytes = new java.io.ByteArrayOutputStream();
        sent.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(java.nio.ByteBuffer buffer) { byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part); }
            public void onError(Throwable error) { throw new AssertionError(error); }
            public void onComplete() { }
        });
        var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(bytes.toByteArray());
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.has("course")).isTrue();
        assertThat(json.has("questions")).isTrue();
    }
    @Test void rejectsUnimplementedResponseBeforeHttpEvenWithMatchingEchoAndV1Body() {
        when(transport.exchange(any(), any())).thenReturn(response(200,
                "{\"items\":[{\"questionId\":101,\"knowledgePoint\":\"dates\"}]}",
                Map.of("X-Enrichment-Prompt-Version", List.of("2"), "X-Enrichment-Response-Version", List.of("2"))));
        assertThatThrownBy(() -> client(true).enrichKnowledgePoints(request(), new EnrichmentContract("1", "2", "2")))
                .isInstanceOf(PermanentJobException.class).hasMessage("ENRICHMENT_CONTRACT_UNAVAILABLE").hasNoCause();
        verify(transport, never()).exchange(any(), any());
    }

    @Test void newPromptWithImplementedResponseUsesPinnedHeaders() {
        when(transport.exchange(any(), any())).thenReturn(response(200, "{\"items\":[]}",
                Map.of("X-Enrichment-Prompt-Version", List.of("2"), "X-Enrichment-Response-Version", List.of("1"))));
        assertThat(client(true).enrichKnowledgePoints(request(), new EnrichmentContract("1", "2", "1")).items()).isEmpty();
        var capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).exchange(capture.capture(), any());
        assertThat(capture.getValue().headers().firstValue("X-Enrichment-Prompt-Version")).contains("2");
        assertThat(capture.getValue().headers().firstValue("X-Enrichment-Response-Version")).contains("1");
    }

    @Test void disabledDoesNotPerformIo() {
        assertThatThrownBy(() -> client(false).enrichKnowledgePoints(request(), EnrichmentContract.V1))
                .isInstanceOf(PermanentJobException.class).hasMessage("ENRICHMENT_DISABLED").hasNoCause();
        verify(transport, never()).exchange(any(), any());
    }
    @Test void requiresSingleMatchingExecutedTuple() {
        for (var headers : List.of(Map.<String,List<String>>of(), Map.of("X-Enrichment-Prompt-Version",List.of("2")),
                Map.of("X-Enrichment-Prompt-Version",List.of("1","1"),"X-Enrichment-Response-Version",List.of("1")))) {
            when(transport.exchange(any(),any())).thenReturn(response(200,"{\"items\":[]}", headers));
            assertThatThrownBy(() -> client(true).enrichKnowledgePoints(request(),EnrichmentContract.V1))
                    .isInstanceOf(PermanentJobException.class).hasMessage("ENRICHMENT_CONTRACT_UNAVAILABLE");
        }
    }
    @ParameterizedTest @ValueSource(strings={"{}", "{\"items\":[]} {\"extra\":true}", "{\"items\":{}}", "{\"items\":[null]}",
            "{\"items\":[{\"questionId\":\"101\",\"knowledgePoint\":\"dates\"}]}",
            "{\"items\":[{\"questionId\":9223372036854775808,\"knowledgePoint\":\"dates\"}]}",
            "{\"items\":[{\"questionId\":0,\"knowledgePoint\":\"dates\"}]}",
            "{\"items\":[{\"questionId\":1,\"knowledgePoint\":123}]}"})
    void rejectsInvalidShape(String body) {
        when(transport.exchange(any(),any())).thenReturn(response(200,body,echo()));
        assertThatThrownBy(() -> client(true).enrichKnowledgePoints(request(),EnrichmentContract.V1))
                .isInstanceOf(RetryableJobException.class).hasMessage("ENRICHMENT_INVALID_RESPONSE").hasNoCause();
    }
    @Test void rejectsDeepJsonSafely() { rejectsInvalidShape("[".repeat(100)+"0"+"]".repeat(100)); }
    @Test void classifiesFiniteCodesAndRetryAfter() {
        for (int status : new int[]{400,401,413}) {
            when(transport.exchange(any(),any())).thenReturn(response(status,"secret",Map.of()));
            assertThatThrownBy(() -> client(true).enrichKnowledgePoints(request(),EnrichmentContract.V1)).isInstanceOf(PermanentJobException.class).hasNoCause();
        }
        when(transport.exchange(any(),any())).thenReturn(response(409,"{\"code\":\"ENRICHMENT_CONTRACT_UNSUPPORTED\"}",Map.of()));
        assertThatThrownBy(() -> client(true).enrichKnowledgePoints(request(),EnrichmentContract.V1)).hasMessage("ENRICHMENT_CONTRACT_UNAVAILABLE");
        when(transport.exchange(any(),any())).thenReturn(response(503,"{\"code\":\"ENRICHMENT_CONFIGURATION_UNAVAILABLE\"}",Map.of()));
        assertThatThrownBy(() -> client(true).enrichKnowledgePoints(request(),EnrichmentContract.V1)).isInstanceOf(PermanentJobException.class).hasMessage("ENRICHMENT_CONFIGURATION_UNAVAILABLE");
        when(transport.exchange(any(),any())).thenReturn(response(429,"secret",Map.of("Retry-After",List.of("12"))));
        var error = catchThrowableOfType(() -> client(true).enrichKnowledgePoints(request(),EnrichmentContract.V1), RetryableJobException.class);
        assertThat(error.getRetryAfter()).isEqualTo(Duration.ofSeconds(12));
        assertThat(error).hasMessage("THROTTLED").hasNoCause();
    }
    @Test void safeRetryAfterDateAndInvalidValues() {
        String date = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(60)
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
        when(transport.exchange(any(),any())).thenReturn(response(503,"secret",Map.of("Retry-After",List.of(date))));
        var dated = catchThrowableOfType(() -> client(true).enrichKnowledgePoints(request(),EnrichmentContract.V1),RetryableJobException.class);
        assertThat(dated.getRetryAfter()).isBetween(Duration.ofSeconds(55),Duration.ofSeconds(60));
        for (var values : List.of(List.of("999999999999999999999999999999"), List.of("-1"),List.of("86401"),List.of("secret"),List.of("1","2"))) {
            when(transport.exchange(any(),any())).thenReturn(response(429,"secret",Map.of("Retry-After",values)));
            var error = catchThrowableOfType(() -> client(true).enrichKnowledgePoints(request(),EnrichmentContract.V1),RetryableJobException.class);
            assertThat(error.getRetryAfter()).isNull();
            assertThat(error).hasNoCause().hasMessage("THROTTLED");
        }
    }
    @Test void preservesKnown502CodesAndSanitizesUnknown503() {
        for (String code : List.of("ENRICHMENT_DEADLINE_EXCEEDED","ENRICHMENT_GENERATION_FAILED")) {
            when(transport.exchange(any(),any())).thenReturn(response(502,"{\"code\":\""+code+"\"}",Map.of()));
            assertThatThrownBy(() -> client(true).enrichKnowledgePoints(request(),EnrichmentContract.V1)).isInstanceOf(RetryableJobException.class).hasMessage(code).hasNoCause();
        }
        when(transport.exchange(any(),any())).thenReturn(response(503,"{\"code\":\"secret\"}",Map.of()));
        assertThatThrownBy(() -> client(true).enrichKnowledgePoints(request(),EnrichmentContract.V1)).hasMessage("ENRICHMENT_UNAVAILABLE").hasNoCause();
    }
}
