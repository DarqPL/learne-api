package com.learne.backend.mail.service;

import org.springframework.mail.SimpleMailMessage;

/** Provider boundary; production implementation is supplied in M3. */
@FunctionalInterface
public interface MailTransport {
    void send(SimpleMailMessage message);
}
