package com.learne.backend.auth.session.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PasswordResetMailService {
    private final JavaMailSender mailSender;
    private final String mailFrom;

    @Autowired
    public PasswordResetMailService(JavaMailSender mailSender, @Value("${app.mail.from:}") String mailFrom) {
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
    }

    public PasswordResetMailService(JavaMailSender mailSender) {
        this(mailSender, "");
    }

    public void sendPasswordResetToken(String email, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (StringUtils.hasText(mailFrom)) {
            message.setFrom(mailFrom);
        }
        message.setTo(email);
        message.setSubject("LearnE password reset");
        message.setText("Your LearnE password reset token is " + token + ". It expires in 15 minutes.");
        mailSender.send(message);
    }
}
