package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.mail.model.MailPayload;
import com.learne.backend.mail.model.MailTemplate;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MailPayloadTemplateTests {
    @Test void recipientNameMatchesExistingDatabaseBoundAndRejectsHeaderControls() {
        var codec = new com.learne.backend.mail.service.MailPayloadCodec(JsonMapper.builder().build());
        for (String name : java.util.List.of("N".repeat(201), "N".repeat(255), "N".repeat(256), "Name\r\nInjected")) {
            var p = new MailPayload(0, "student@example.test", name, MailTemplate.REGISTRATION_OTP,
                    java.util.UUID.randomUUID().toString(), Instant.now().plusSeconds(300), null, null, null, null, null, Map.of());
            if (name.length() <= 255 && !name.contains("\r"))
                assertThat(codec.canonicalize(p.template().jobType(), p).recipientName()).isEqualTo(name);
            else assertThatThrownBy(() -> codec.canonicalize(p.template().jobType(), p)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test
    void templatesMapToExactJobTypes() {
        assertThat(MailTemplate.values()).hasSize(6);
        for (MailTemplate value : MailTemplate.values()) {
            assertThat(value.jobType().name()).isEqualTo(value.name() + "_MAIL");
        }
    }

    @Test
    void payloadCopiesValuesUsesCamelCaseJsonAndRedactsDiagnostics() throws Exception {
        var values = new HashMap<String, String>();
        values.put("courseName", "Private course");
        var payload = new MailPayload(0L, "private@example.test", "Private recipient", MailTemplate.REGISTRATION_OTP,
                        "private-generation", Instant.parse("2026-10-18T00:00:00Z"), null, null, null, null, null, values);
        values.put("courseName", "Changed");
        Map<String, String> copied = payload.values();
        assertThat(copied).containsEntry("courseName", "Private course");
        assertThatThrownBy(() -> copied.put("other", "value")).isInstanceOf(UnsupportedOperationException.class);
        var mapper = JsonMapper.builder().build();
        var json = mapper.readTree(mapper.writeValueAsString(payload));
        assertThat(json.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "recipientUserId", "recipientEmail", "recipientName", "template", "generation", "expiresAt",
                "courseId", "revisionId", "submissionId", "decisionId", "orderId", "values");
        assertThat(json.get("recipientUserId").asLong()).isZero();
        assertThat(json.get("generation").asString()).isEqualTo("private-generation");
        assertThat(mapper.readValue(mapper.writeValueAsString(payload), MailPayload.class)).isEqualTo(payload);
        assertThat(payload.toString()).doesNotContain("private@example.test", "Private recipient", "Private course", "private-generation");
    }
}
