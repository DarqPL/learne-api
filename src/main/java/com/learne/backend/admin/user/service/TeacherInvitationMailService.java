package com.learne.backend.admin.user.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.MailJobProducer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class TeacherInvitationMailService {
    private final MailJobProducer producer;
    public TeacherInvitationMailService(MailJobProducer producer) { this.producer = producer; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sendTeacherInvitation(User user, String password, UUID generation, Instant deadline) {
        var payload = new MailPayload(user.getId(), user.getEmail(), user.getFullName(),
                MailTemplate.TEACHER_INVITATION, generation.toString(), deadline,
                null, null, null, null, null, Map.of());
        byte[] secret = password.getBytes(StandardCharsets.UTF_8);
        try { producer.enqueue(MailTemplate.TEACHER_INVITATION.jobType(),
                "teacher-invitation:" + user.getId() + ":" + generation, payload, secret); }
        finally { Arrays.fill(secret, (byte) 0); }
    }
}
