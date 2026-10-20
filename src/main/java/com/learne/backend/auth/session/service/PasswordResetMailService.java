package com.learne.backend.auth.session.service;

import com.learne.backend.domain.auth.PasswordResetToken;
import com.learne.backend.mail.model.MailPayload;
import com.learne.backend.mail.model.MailTemplate;
import com.learne.backend.mail.service.MailJobProducer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetMailService {
    private final MailJobProducer producer;

    public PasswordResetMailService(MailJobProducer producer) { this.producer = producer; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sendPasswordResetToken(PasswordResetToken token, String code) {
        var user = token.getUser();
        String generation = token.getId().toString();
        var payload = new MailPayload(user.getId(), user.getEmail(), user.getFullName(),
                MailTemplate.PASSWORD_RESET, generation, token.getExpiresAt().toInstant(),
                null, null, null, null, null, Map.of());
        byte[] secret = code.getBytes(StandardCharsets.UTF_8);
        try { producer.enqueue(MailTemplate.PASSWORD_RESET.jobType(), "password-reset:" + generation, payload, secret); }
        finally { Arrays.fill(secret, (byte) 0); }
    }
}
