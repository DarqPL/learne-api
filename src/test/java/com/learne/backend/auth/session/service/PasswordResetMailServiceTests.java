package com.learne.backend.auth.session.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learne.backend.domain.auth.*;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.MailJobProducer;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PasswordResetMailServiceTests {
    @Test void snapshotsRealTokenIdAndRecipient() {
        var producer = mock(MailJobProducer.class);
        var user = new User(); ReflectionTestUtils.setField(user, "id", 7L);
        user.setEmail("student@example.com"); user.setFullName("Student");
        var token = new PasswordResetToken(); ReflectionTestUtils.setField(token, "id", 42L);
        token.setUser(user); token.setExpiresAt(OffsetDateTime.now().plusMinutes(15));
        doAnswer(call -> {
            MailPayload p = call.getArgument(2);
            assertThat(p.recipientUserId()).isEqualTo(7);
            assertThat(p.recipientEmail()).isEqualTo(user.getEmail());
            assertThat(p.generation()).isEqualTo("42");
            assertThat(p.expiresAt()).isEqualTo(token.getExpiresAt().toInstant());
            assertThat(p.values()).isEmpty();
            assertThat((byte[]) call.getArgument(3)).isEqualTo("123456".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return UUID.randomUUID();
        }).when(producer).enqueue(eq(MailTemplate.PASSWORD_RESET.jobType()), eq("password-reset:42"), any(), any());
        new PasswordResetMailService(producer).sendPasswordResetToken(token, "123456");
        verify(producer).enqueue(eq(MailTemplate.PASSWORD_RESET.jobType()), eq("password-reset:42"), any(), any());
    }
}
