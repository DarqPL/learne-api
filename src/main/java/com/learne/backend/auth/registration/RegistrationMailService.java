package com.learne.backend.auth.registration;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class RegistrationMailService {
    private final JavaMailSender mailSender;

    public RegistrationMailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendRegistrationOtp(String email, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("LearnE registration OTP");
        message.setText("Your LearnE registration OTP is " + otp + ". It expires in 5 minutes.");
        mailSender.send(message);
    }
}
