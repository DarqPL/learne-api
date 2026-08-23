package com.learne.backend.auth.session.service;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetMailService {
    private final JavaMailSender mailSender;

    public PasswordResetMailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendPasswordResetToken(String email, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("LearnE password reset");
        message.setText("Your LearnE password reset token is " + token + ". It expires in 15 minutes.");
        mailSender.send(message);
    }
}
