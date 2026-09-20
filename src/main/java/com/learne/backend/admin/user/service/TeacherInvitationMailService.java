package com.learne.backend.admin.user.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TeacherInvitationMailService {
    private final JavaMailSender mailSender;
    private final String mailFrom;

    @Autowired
    public TeacherInvitationMailService(JavaMailSender mailSender, @Value("${app.mail.from:}") String mailFrom) {
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
    }

    public TeacherInvitationMailService(JavaMailSender mailSender) {
        this(mailSender, "");
    }

    public void sendTeacherInvitation(String email, String fullName, String temporaryPassword) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (StringUtils.hasText(mailFrom)) {
            message.setFrom(mailFrom);
        }
        message.setTo(email);
        message.setSubject("LearnE teacher account created");
        message.setText("Hello " + fullName + ", your LearnE teacher account has been created. "
                + "Your temporary password is " + temporaryPassword + ". Please change it after signing in.");
        mailSender.send(message);
    }
}
