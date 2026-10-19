package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;

import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.MailTemplateRenderer;
import com.learne.backend.mail.service.MailPayloadCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MailTemplateRendererTests {
    private final MailTemplateRenderer renderer = new MailTemplateRenderer("https://learne.test", "LearnE <mail@learne.test>");

    static MailPayload payload(MailTemplate template, Map<String, String> values) {
        boolean auth = template.ordinal() < 3;
        return new MailPayload(template == MailTemplate.REGISTRATION_OTP ? 0 : 7,
                "student@example.test", "<b>Literal name</b>", template,
                auth ? template == MailTemplate.PASSWORD_RESET ? "42" : UUID.randomUUID().toString() : null,
                auth ? Instant.parse("2030-01-01T00:00:00Z") : null,
                template == MailTemplate.COURSE_PENDING_REVIEW || template == MailTemplate.COURSE_REVIEW_RESULT ? 11L : null,
                template == MailTemplate.COURSE_PENDING_REVIEW || template == MailTemplate.COURSE_REVIEW_RESULT ? 12L : null,
                template == MailTemplate.COURSE_PENDING_REVIEW || template == MailTemplate.COURSE_REVIEW_RESULT ? UUID.randomUUID() : null,
                template == MailTemplate.COURSE_REVIEW_RESULT ? UUID.randomUUID() : null,
                template == MailTemplate.PAYMENT_CONFIRMATION ? 13L : null, values);
    }

    @Test void rendersPaymentWithTrustedRouteAndVndAndLiteralText() {
        var message = renderer.render(payload(MailTemplate.PAYMENT_CONFIRMATION,
                Map.of("amountVnd", "1200000", "itemDescription", "<img src=x>", "paidAt", "2026-10-18T01:00:00Z")), null);
        assertThat(message.getTo()).containsExactly("student@example.test");
        assertThat(message.getFrom()).isEqualTo("LearnE <mail@learne.test>");
        assertThat(message.getText()).contains("1.200.000 VND", "<img src=x>", "https://learne.test/student/payment/orders/13");
    }
    @Test void rendersExactNonnegativeLongBoundaryAmountsWithoutOverflow() {
        for (var entry : Map.of("0", "0 VND", "9223372036854775807", "9.223.372.036.854.775.807 VND").entrySet()) {
            var message = renderer.render(payload(MailTemplate.PAYMENT_CONFIRMATION,
                    Map.of("amountVnd", entry.getKey(), "itemDescription", "Receipt", "paidAt", "2026-10-19T00:00:00Z")), null);
            assertThat(message.getText()).contains("Amount: " + entry.getValue()).doesNotContain("Amount: -");
        }
    }

    @Test void reviewRendersHistoricalReasonLiterally() {
        var message = renderer.render(payload(MailTemplate.COURSE_REVIEW_RESULT,
                Map.of("courseName", "Course", "decision", "REJECTED", "rejectionReason", "Snapshot <reason>",
                        "reviewedAt", "2026-10-18T01:00:00Z")), null);
        assertThat(message.getText()).contains("Snapshot <reason>", "https://learne.test/teacher/content/courses/11");
    }
    private MailPayload withoutSubmission(MailPayload original) {
        return new MailPayload(original.recipientUserId(), original.recipientEmail(), original.recipientName(), original.template(),
                original.generation(), original.expiresAt(), original.courseId(), original.revisionId(), null,
                original.decisionId(), original.orderId(), original.values());
    }
    @Test void legacyReviewWithoutSubmissionDecodesAndRendersWithRequiredDecisionAndCourseIds() {
        var legacy = withoutSubmission(payload(MailTemplate.COURSE_REVIEW_RESULT,
                Map.of("courseName", "Course", "decision", "REJECTED", "rejectionReason", "Historical reason",
                        "reviewedAt", "2026-10-18T01:00:00Z")));
        var codec = new MailPayloadCodec(tools.jackson.databind.json.JsonMapper.builder().build());
        assertThat(codec.decode(legacy.template().jobType(), codec.encode(legacy))).isEqualTo(legacy);
        assertThat(renderer.render(legacy, null).getText()).contains("Historical reason", "/teacher/content/courses/11");
    }
    @Test void pendingReviewStillRequiresSubmissionForBothCodecAndRenderer() {
        var pending = withoutSubmission(payload(MailTemplate.COURSE_PENDING_REVIEW,
                Map.of("courseName", "Course", "teacherName", "Teacher", "submittedAt", "2026-10-18T01:00:00Z")));
        var codec = new MailPayloadCodec(tools.jackson.databind.json.JsonMapper.builder().build());
        assertThatThrownBy(() -> codec.decode(pending.template().jobType(), codec.encode(pending)))
                .hasMessage("Invalid mail payload").hasNoCause();
        assertThatThrownBy(() -> renderer.render(pending, null)).hasMessage("Invalid mail payload").hasNoCause();
    }

    @Test void rendererRejectsUnvalidatedMetadataAndPreservesMultilineUserText() {
        assertThatThrownBy(() -> renderer.render(payload(MailTemplate.PAYMENT_CONFIRMATION,
                Map.of("amountVnd", "1", "itemDescription", "Item", "paidAt", "2026-10-18T01:00:00Z", "url", "https://attacker.test")), null))
                .hasMessage("Invalid mail payload").hasNoCause();
        var message = renderer.render(payload(MailTemplate.COURSE_REVIEW_RESULT,
                Map.of("courseName", "Course", "decision", "REJECTED", "rejectionReason", "First line\nSecond line",
                        "reviewedAt", "2026-10-18T01:00:00Z")), null);
        assertThat(message.getText()).contains("First line\nSecond line");
    }

    @Test void pendingUsesAdminRouteAndOptionalFrom() {
        var message = new MailTemplateRenderer("http://localhost:5173/", "").render(
                payload(MailTemplate.COURSE_PENDING_REVIEW, Map.of("courseName", "Course", "teacherName", "Teacher",
                        "submittedAt", "2026-10-18T01:00:00Z")), null);
        assertThat(message.getFrom()).isNull();
        assertThat(message.getText()).contains("http://localhost:5173/admin/reviews", "Teacher");
    }

    @Test void credentialsOnlyRenderInAuthMessages() {
        for (var template : new MailTemplate[]{MailTemplate.REGISTRATION_OTP, MailTemplate.PASSWORD_RESET, MailTemplate.TEACHER_INVITATION}) {
            var message = renderer.render(payload(template, Map.of()), "private-marker".getBytes(StandardCharsets.UTF_8));
            assertThat(message.getText()).contains("private-marker");
            if (template == MailTemplate.TEACHER_INVITATION) assertThat(message.getText()).contains("https://learne.test/login");
            assertThatThrownBy(() -> renderer.render(payload(template, Map.of()), null)).hasMessage("Invalid mail secret");
        }
        assertThatThrownBy(() -> renderer.render(payload(MailTemplate.PAYMENT_CONFIRMATION,
                Map.of("amountVnd", "1", "itemDescription", "Item", "paidAt", "2026-10-18T01:00:00Z")), new byte[]{1}))
                .hasMessage("Invalid mail secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://learne.test", "https://user:pass@learne.test", "https://learne.test?q=x",
            "https://learne.test/#fragment", "//learne.test", "https://learne.test/path", "garbage"})
    void rejectsUntrustedBaseUris(String base) {
        assertThatThrownBy(() -> new MailTemplateRenderer(base, "")).hasMessage("Invalid mail frontend base URI").hasNoCause();
    }
}
