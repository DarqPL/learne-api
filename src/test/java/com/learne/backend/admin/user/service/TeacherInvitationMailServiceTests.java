package com.learne.backend.admin.user.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learne.backend.domain.auth.User;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.MailJobProducer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeacherInvitationMailServiceTests {
    @Test void queuesSnapshotWithExplicitGenerationDeadlineAndWipesOwnedSecret() {
        var producer = mock(MailJobProducer.class);
        var user = new User(); org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 42L); user.setEmail("teacher@example.test"); user.setFullName("Teacher");
        var generation = UUID.randomUUID(); var deadline = Instant.parse("2026-10-20T00:00:00Z");
        byte[][] owned = new byte[1][];
        doAnswer(call -> {
            MailPayload p = call.getArgument(2); owned[0] = call.getArgument(3);
            assertThat(p.recipientUserId()).isEqualTo(42L);
            assertThat(p.generation()).isEqualTo(generation.toString());
            assertThat(p.expiresAt()).isEqualTo(deadline);
            assertThat(new String(owned[0], java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("temporary-password");
            return UUID.randomUUID();
        }).when(producer).enqueue(eq(MailTemplate.TEACHER_INVITATION.jobType()), eq("teacher-invitation:42:" + generation), any(), any());
        new TeacherInvitationMailService(producer).sendTeacherInvitation(user, "temporary-password", generation, deadline);
        assertThat(owned[0]).containsOnly((byte) 0);
    }
}
