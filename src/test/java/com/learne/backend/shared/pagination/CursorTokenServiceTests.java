package com.learne.backend.shared.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import com.learne.backend.shared.api.ApiException;

class CursorTokenServiceTests {
    private final CursorTokenService service = new CursorTokenService("test-secret-for-pagination-cursors");

    @Test
    void encodesAndDecodesBoundCursor() {
        String token = service.encode("student-courses", "publishedAtDescIdDesc", Map.of("level", "A1"),
                Map.of("publishedAt", "2026-10-01T00:00:00Z", "id", "10"));

        CursorTokenService.DecodedCursor decoded = service.decode(token, "student-courses", "publishedAtDescIdDesc",
                Map.of("level", "A1"));

        assertThat(decoded.sortValues()).containsEntry("publishedAt", "2026-10-01T00:00:00Z");
        assertThat(decoded.sortValues()).containsEntry("id", "10");
    }

    @Test
    void rejectsDifferentFilters() {
        String token = service.encode("student-courses", "publishedAtDescIdDesc", Map.of("level", "A1"),
                Map.of("publishedAt", "2026-10-01T00:00:00Z", "id", "10"));

        assertThatThrownBy(() -> service.decode(token, "student-courses", "publishedAtDescIdDesc",
                Map.of("level", "B1")))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsTamperedToken() {
        String token = service.encode("student-courses", "publishedAtDescIdDesc", Map.of(),
                Map.of("publishedAt", "2026-10-01T00:00:00Z", "id", "10"));
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> service.decode(tampered, "student-courses", "publishedAtDescIdDesc", Map.of()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> service.decode("not-a-valid-token", "student-courses", "publishedAtDescIdDesc",
                Map.of()))
                .isInstanceOf(ApiException.class);
    }
}
