package com.learne.backend.auth.registration.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learne.backend.domain.auth.RegistrationOtp;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.MailJobProducer;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegistrationMailServiceTests {
    @Test void snapshotsPendingGenerationAndEncryptsOnlySecret() {
        var producer = mock(MailJobProducer.class);
        var pending = new RegistrationOtp();
        pending.setEmail("student@example.com"); pending.setFullName("N".repeat(255));
        pending.setMailGeneration(UUID.randomUUID()); pending.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        doAnswer(call -> {
            MailPayload p = call.getArgument(2);
            assertThat(p.recipientUserId()).isZero();
            assertThat(p.recipientName()).isEqualTo(pending.getFullName());
            assertThat(p.generation()).isEqualTo(pending.getMailGeneration().toString());
            assertThat(p.expiresAt()).isEqualTo(pending.getExpiresAt().toInstant());
            assertThat(p.values()).isEmpty();
            assertThat((byte[]) call.getArgument(3)).isEqualTo("123456".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return UUID.randomUUID();
        }).when(producer).enqueue(eq(MailTemplate.REGISTRATION_OTP.jobType()), eq("registration-otp:" + pending.getMailGeneration()), any(), any());
        new RegistrationMailService(producer).sendRegistrationOtp(pending, "123456");
        verify(producer).enqueue(eq(MailTemplate.REGISTRATION_OTP.jobType()), eq("registration-otp:" + pending.getMailGeneration()), any(), any());
    }
}
