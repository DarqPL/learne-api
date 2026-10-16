package com.learne.backend.ratelimit;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class RateLimitErrorResponseFactory {
    // Metadata for the discarded representation, plus headers supplied by the shared error contract.
    private static final Set<String> REPLACED_HEADERS = Set.of(
            "content-length", "content-type", "content-encoding", "content-range", "content-language",
            "content-location", "content-disposition", "content-md5", "content-digest", "repr-digest", "digest",
            "transfer-encoding", "trailer", "etag", "last-modified", "expires", "age", "accept-ranges",
            "cache-control", "retry-after");

    private final ObjectMapper objectMapper;

    public RateLimitErrorResponseFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse servletResponse, RateLimitException exception) throws IOException {
        if (servletResponse.isCommitted()) {
            return;
        }
        var entity = response(exception);
        byte[] json = objectMapper.writeValueAsBytes(entity.getBody());
        Map<String, List<String>> retainedHeaders = new LinkedHashMap<>();
        servletResponse.getHeaderNames().forEach(name -> {
            if (!REPLACED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                retainedHeaders.put(name, List.copyOf(servletResponse.getHeaders(name)));
            }
        });
        // resetBuffer() retains a previously selected writer's charset. Reset the entire uncommitted
        // response so the replacement bytes cannot be transcoded or appended to partial content.
        servletResponse.reset();
        retainedHeaders.forEach((name, values) -> values.forEach(value -> servletResponse.addHeader(name, value)));
        servletResponse.setStatus(entity.getStatusCode().value());
        servletResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        entity.getHeaders().forEach((name, values) -> servletResponse.setHeader(name, String.join(", ", values)));
        servletResponse.setContentLength(json.length);
        servletResponse.getOutputStream().write(json);
    }

    public static ResponseEntity<RateLimitErrorResponse> response(RateLimitException exception) {
        boolean rejected = exception.reason() == RateLimitException.Reason.REJECTED;
        var builder = ResponseEntity.status(rejected ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (rejected) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, (exception.retryDelayMs() + 999) / 1000)));
        }
        return builder.body(rejected
                ? new RateLimitErrorResponse("RATE_LIMITED", "Too many requests. Please try again later.")
                : new RateLimitErrorResponse("ABUSE_PROTECTION_UNAVAILABLE", "Service temporarily unavailable. Please try again later."));
    }
}
