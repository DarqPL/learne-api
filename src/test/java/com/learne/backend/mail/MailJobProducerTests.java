package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.jobs.crypto.*;
import com.learne.backend.jobs.model.*;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

class MailJobProducerTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<JobSpec> captured = new AtomicReference<>();
    private JobPayloadCipher cipher(boolean key) {
        var p = new JobCryptoProperties();
        if (key) { p.setActiveKeyId("v1"); p.setKeysJson("{\"v1\":\"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=\"}"); }
        return new JobPayloadCipher(p, mapper);
    }
    private MailJobProducer producer() {
        return new MailJobProducer(spec -> { captured.set(spec); return spec.id(); }, cipher(true), mapper);
    }
    private MailPayload auth(String email) {
        return new MailPayload(0, email, "Student", MailTemplate.REGISTRATION_OTP, UUID.randomUUID().toString(),
                Instant.parse("2030-01-01T00:00:00Z"), null, null, null, null, null, Map.of());
    }
    @Test void encryptsWithAssignedIdentityAndCanonicalizesEmailWithoutSerializingSecret() {
        byte[] secret = "private-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var payload = auth("  STUDENT@Example.Test  ");
        var id = producer().enqueue(JobType.REGISTRATION_OTP_MAIL, "trusted-dedupe", payload, secret);
        var spec = captured.get();
        assertThat(id).isEqualTo(spec.id());
        assertThat(spec.queue()).isEqualTo(QueueName.MAIL_AUTH);
        assertThat(spec.priority()).isZero();
        assertThat(spec.payloadVersion()).isOne();
        assertThat(spec.dedupeKey()).isEqualTo("trusted-dedupe");
        assertThat(spec.expiresAt()).isEqualTo(payload.expiresAt());
        assertThat(spec.payloadJson()).contains("student@example.test").doesNotContain("private-marker");
        assertThat(cipher(true).decrypt(id, spec.type(), payload.generation(), spec.secret())).containsExactly(secret);
        assertThat(secret).containsExactly("private-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void missingKeyFailsWhenProducerInstalled() {
        assertThatThrownBy(() -> new MailJobProducer(spec -> spec.id(), cipher(false), mapper))
                .hasMessage("JOB_CRYPTO_ACTIVE_KEY_REQUIRED");
    }
    @Test void resetGenerationIdentifiesExistingScalarTokenRatherThanInventingUuid() {
        var p = new MailPayload(7, "student@example.test", "Student", MailTemplate.PASSWORD_RESET, "42",
                Instant.parse("2030-01-01T00:00:00Z"), null, null, null, null, null, Map.of());
        producer().enqueue(JobType.PASSWORD_RESET_MAIL, "reset:42", p, new byte[]{65});
        assertThat(new MailPayloadCodec(mapper).decode(JobType.PASSWORD_RESET_MAIL, captured.get().payloadJson())).isEqualTo(p);
    }
    @Test void requiresCallerTransaction() throws Exception {
        assertThat(MailJobProducer.class.getMethod("enqueue", JobType.class, String.class, MailPayload.class, byte[].class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }
    @Test void rejectsMismatchedTypeAndSecretSizeWithoutRetainingSecrets() {
        assertThatThrownBy(() -> producer().enqueue(JobType.PASSWORD_RESET_MAIL, "d", auth("a@b.test"), new byte[]{1}))
                .hasMessage("Invalid mail payload").hasNoCause();
        for (byte[] secret : new byte[][]{null, new byte[0], new byte[4097]}) {
            assertThatThrownBy(() -> producer().enqueue(JobType.REGISTRATION_OTP_MAIL, "d", auth("a@b.test"), secret))
                    .hasMessage("Invalid mail secret").hasNoCause();
        }
        assertThat(captured).hasValue(null);
    }
    @Test void invitationUsesLowerPriorityAndNoticesHaveNoExpiryOrSecret() {
        var producer = producer();
        producer.enqueue(JobType.TEACHER_INVITATION_MAIL, "invite", MailTemplateRendererTests.payload(MailTemplate.TEACHER_INVITATION, Map.of()), new byte[]{65});
        assertThat(captured.get().priority()).isOne();
        producer.enqueue(JobType.PAYMENT_CONFIRMATION_MAIL, "paid", MailTemplateRendererTests.payload(MailTemplate.PAYMENT_CONFIRMATION,
                Map.of("amountVnd", "100", "itemDescription", "Item", "paidAt", "2026-10-18T01:00:00Z")), null);
        assertThat(captured.get().queue()).isEqualTo(QueueName.MAIL_NOTICE);
        assertThat(captured.get().expiresAt()).isNull();
        assertThat(captured.get().secret()).isNull();
    }
    @ParameterizedTest @ValueSource(strings = {"{\"secret\":\"private-marker\"}", "{}", "null", "[]", "{} {}"})
    void strictDecoderSanitizesMalformedPayloads(String json) {
        assertThatThrownBy(() -> new MailPayloadCodec(mapper).decode(JobType.REGISTRATION_OTP_MAIL, json))
                .hasMessage("Invalid mail payload").hasNoCause();
    }
    @Test void strictDecoderRejectsUnknownAndCoercedFieldsAndRoundTripsValidPayload() {
        var codec = new MailPayloadCodec(mapper);
        var payload = auth("a@b.test");
        String json = mapper.writeValueAsString(payload);
        assertThat(codec.decode(JobType.REGISTRATION_OTP_MAIL, json)).isEqualTo(payload);
        for (String invalid : List.of(json.replace("\"recipientUserId\":0", "\"recipientUserId\":\"0\""),
                json.replaceFirst("\\{", "{\"secret\":\"private-marker\","),
                json.replace("\"values\":{}", "\"values\":{\"code\":\"private-marker\"}"))) {
            assertThatThrownBy(() -> codec.decode(JobType.REGISTRATION_OTP_MAIL, invalid))
                    .hasMessage("Invalid mail payload").hasNoCause();
        }
    }
}
