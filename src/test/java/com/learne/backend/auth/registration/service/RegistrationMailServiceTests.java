package com.learne.backend.auth.registration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class RegistrationMailServiceTests {

    @Mock
    private JavaMailSender mailSender;

    @Test
    void sendsRegistrationOtpFromConfiguredSender() {
        RegistrationMailService service = new RegistrationMailService(mailSender, "LearnE <no-reply@connection-app.online>");

        service.sendRegistrationOtp("student@example.com", "123456");

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getFrom()).isEqualTo("LearnE <no-reply@connection-app.online>");
    }
}
