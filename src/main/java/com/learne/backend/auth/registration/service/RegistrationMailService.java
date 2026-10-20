package com.learne.backend.auth.registration.service;

import com.learne.backend.domain.auth.RegistrationOtp;
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
public class RegistrationMailService {
    private final MailJobProducer producer;

    public RegistrationMailService(MailJobProducer producer) { this.producer = producer; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sendRegistrationOtp(RegistrationOtp pending, String otp) {
        String generation = pending.getMailGeneration().toString();
        var payload = new MailPayload(0, pending.getEmail(), pending.getFullName(),
                MailTemplate.REGISTRATION_OTP, generation, pending.getExpiresAt().toInstant(),
                null, null, null, null, null, Map.of());
        byte[] secret = otp.getBytes(StandardCharsets.UTF_8);
        try { producer.enqueue(MailTemplate.REGISTRATION_OTP.jobType(), "registration-otp:" + generation, payload, secret); }
        finally { Arrays.fill(secret, (byte) 0); }
    }
}
