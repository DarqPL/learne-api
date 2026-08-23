package com.learne.backend.admin.user.service;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class TeacherInvitationMailService {
    private final JavaMailSender mailSender;

    public TeacherInvitationMailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendTeacherInvitation(String email, String fullName, String temporaryPassword) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("LearnE teacher account created");
        message.setText("Hello " + fullName + ", your LearnE teacher account has been created. "
                + "Your temporary password is " + temporaryPassword + ". Please change it after signing in.");
        mailSender.send(message);
    }
}
