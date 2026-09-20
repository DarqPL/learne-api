package com.learne.backend.auth.registration.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RegistrationMailService {
    private final JavaMailSender mailSender;
    private final String mailFrom;

    @Autowired
    public RegistrationMailService(JavaMailSender mailSender, @Value("${app.mail.from:}") String mailFrom) {
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
    }

    public RegistrationMailService(JavaMailSender mailSender) {
        this(mailSender, "");
    }

    public void sendRegistrationOtp(String email, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (StringUtils.hasText(mailFrom)) {
            message.setFrom(mailFrom);
        }
        message.setTo(email);
        message.setSubject("LearnE registration OTP");
        message.setText("Your LearnE registration OTP is " + otp + ". It expires in 5 minutes.");
        mailSender.send(message);
    }
}
