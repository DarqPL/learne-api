package com.learne.backend.mail.service;

import com.learne.backend.mail.model.MailPayload;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;
import org.springframework.mail.SimpleMailMessage;

/** Plain text only: recipient text is literal and all navigation originates from trusted configuration. */
public final class MailTemplateRenderer {
    private final String base;
    private final String from;

    public MailTemplateRenderer(String frontendBaseUri, String from) {
        try {
            URI uri = URI.create(frontendBaseUri);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
            this.base = frontendBaseUri.endsWith("/") ? frontendBaseUri.substring(0, frontendBaseUri.length() - 1) : frontendBaseUri;
        } catch (RuntimeException ignored) { throw new IllegalArgumentException("Invalid mail frontend base URI"); }
        if (from == null || from.indexOf('\r') >= 0 || from.indexOf('\n') >= 0) throw new IllegalArgumentException("Invalid mail from");
        this.from = from;
    }

    public SimpleMailMessage render(MailPayload p, byte[] secret) {
        MailPayloadCodec.validate(p);
        boolean auth = MailPayloadCodec.requiresSecret(p.template());
        if (auth ? secret == null || secret.length == 0 || secret.length > 4096 : secret != null)
            throw new IllegalArgumentException("Invalid mail secret");
        String credential = "";
        if (auth) {
            try {
                credential = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(secret)).toString();
                if (credential.isBlank() || credential.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            } catch (CharacterCodingException | IllegalArgumentException ignored) { throw new IllegalArgumentException("Invalid mail secret"); }
        }
        var values = p.values();
        String subject;
        String body;
        switch (p.template()) {
            case REGISTRATION_OTP -> {
                subject = "LearnE registration verification";
                body = "Your verification code is " + credential + ".\nExpires at: " + p.expiresAt();
            }
            case PASSWORD_RESET -> {
                subject = "LearnE password reset";
                body = "Your password reset code is " + credential + ".\nExpires at: " + p.expiresAt();
            }
            case TEACHER_INVITATION -> {
                subject = "LearnE teacher invitation";
                body = "Your temporary password is " + credential + ".\nPlease change it after signing in: " + base + "/login\nExpires at: " + p.expiresAt();
            }
            case PAYMENT_CONFIRMATION -> {
                subject = "LearnE payment confirmation";
                body = values.get("itemDescription") + "\nAmount: "
                        + NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(Long.parseLong(values.get("amountVnd")))
                        + " VND\nPaid at: " + values.get("paidAt") + "\n" + base + "/student/payment/orders/" + p.orderId();
            }
            case COURSE_PENDING_REVIEW -> {
                subject = "LearnE course pending review";
                body = values.get("courseName") + "\nTeacher: " + values.get("teacherName") + "\nSubmitted at: "
                        + values.get("submittedAt") + "\n" + base + "/admin/reviews";
            }
            case COURSE_REVIEW_RESULT -> {
                subject = "LearnE course review result";
                body = values.get("courseName") + "\nDecision: " + values.get("decision") + "\n"
                        + values.get("rejectionReason") + "\nReviewed at: " + values.get("reviewedAt")
                        + "\n" + base + "/teacher/content/courses/" + p.courseId();
            }
            default -> throw new IllegalArgumentException("Invalid mail template");
        }
        var message = new SimpleMailMessage();
        if (!from.isBlank()) message.setFrom(from);
        message.setTo(p.recipientEmail()); message.setSubject(subject);
        message.setText("Hello " + p.recipientName() + ",\n\n" + body);
        return message;
    }
}
