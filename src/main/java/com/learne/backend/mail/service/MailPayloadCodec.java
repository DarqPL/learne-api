package com.learne.backend.mail.service;

import com.learne.backend.jobs.model.JobType;
import com.learne.backend.mail.model.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.*;

/** Version-one metadata contract. Parser diagnostics must never escape this boundary. */
public final class MailPayloadCodec {
    private static final Set<String> FIELDS = Set.of("recipientUserId", "recipientEmail", "recipientName", "template",
            "generation", "expiresAt", "courseId", "revisionId", "submissionId", "decisionId", "orderId", "values");
    private final ObjectMapper mapper;

    public MailPayloadCodec(ObjectMapper mapper) { this.mapper = mapper; }

    public MailPayload decode(JobType type, String json) {
        try {
            if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 32768) throw invalid();
            var tree = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(json);
            if (tree == null || !tree.isObject() || !tree.propertyNames().equals(FIELDS)) throw invalid();
            for (String key : List.of("recipientUserId", "courseId", "revisionId", "orderId")) {
                var node = tree.get(key);
                if (!node.isNull() && (!node.isIntegralNumber() || !node.canConvertToLong())) throw invalid();
            }
            for (String key : List.of("recipientEmail", "recipientName", "template", "generation", "expiresAt", "submissionId", "decisionId")) {
                var node = tree.get(key);
                if (!node.isNull() && !node.isString()) throw invalid();
            }
            if (tree.get("recipientUserId").isNull() || !tree.get("values").isObject()) throw invalid();
            for (var entry : tree.get("values").properties()) if (!entry.getValue().isString()) throw invalid();
            var payload = mapper.readerFor(MailPayload.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(json);
            MailPayload result = (MailPayload) payload;
            validate(type, result, false);
            return result;
        } catch (RuntimeException ignored) { throw invalid(); }
    }

    public String encode(MailPayload payload) {
        try { return mapper.writeValueAsString(payload); }
        catch (RuntimeException ignored) { throw invalid(); }
    }

    public MailPayload canonicalize(JobType type, MailPayload p) {
        validate(type, p, true);
        return new MailPayload(p.recipientUserId(), p.recipientEmail().trim().toLowerCase(Locale.ROOT),
                p.recipientName(), p.template(), p.generation(), p.expiresAt(), p.courseId(), p.revisionId(),
                p.submissionId(), p.decisionId(), p.orderId(), p.values());
    }

    public static boolean requiresSecret(MailTemplate template) {
        return template == MailTemplate.REGISTRATION_OTP || template == MailTemplate.PASSWORD_RESET
                || template == MailTemplate.TEACHER_INVITATION;
    }

    public static void validate(MailPayload payload) {
        if (payload == null || payload.template() == null) throw invalid();
        validate(payload.template().jobType(), payload, false);
    }

    private static void validate(JobType type, MailPayload p, boolean canonicalize) {
        try {
            if (p == null || p.template() == null || p.template().jobType() != type) throw invalid();
            text(p.recipientEmail(), 320, false); text(p.recipientName(), 255, false);
            String email = p.recipientEmail().trim().toLowerCase(Locale.ROOT);
            if (!email.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")
                    || (!canonicalize && !email.equals(p.recipientEmail()))) throw invalid();
            if (p.template() == MailTemplate.REGISTRATION_OTP ? p.recipientUserId() != 0 : p.recipientUserId() <= 0) throw invalid();
            boolean auth = requiresSecret(p.template());
            if (auth) {
                if (p.generation() == null || p.expiresAt() == null) throw invalid();
                if (p.template() == MailTemplate.PASSWORD_RESET) {
                    if (!p.generation().matches("[1-9][0-9]{0,18}") || Long.parseLong(p.generation()) <= 0) throw invalid();
                } else if (!UUID.fromString(p.generation()).toString().equals(p.generation())) throw invalid();
            } else if (p.generation() != null || p.expiresAt() != null) throw invalid();
            boolean course = p.template() == MailTemplate.COURSE_PENDING_REVIEW || p.template() == MailTemplate.COURSE_REVIEW_RESULT;
            if (course) {
                if (p.courseId() == null || p.courseId() <= 0 || p.revisionId() == null || p.revisionId() <= 0) throw invalid();
                if (p.template() == MailTemplate.COURSE_PENDING_REVIEW && p.submissionId() == null) throw invalid();
            } else if (p.courseId() != null || p.revisionId() != null || p.submissionId() != null) throw invalid();
            if (p.template() == MailTemplate.COURSE_REVIEW_RESULT ? p.decisionId() == null : p.decisionId() != null) throw invalid();
            if (p.template() == MailTemplate.PAYMENT_CONFIRMATION) {
                if (p.orderId() == null || p.orderId() <= 0) throw invalid();
            } else if (p.orderId() != null) throw invalid();
            Set<String> keys = switch (p.template()) {
                case REGISTRATION_OTP, PASSWORD_RESET, TEACHER_INVITATION -> Set.of();
                case PAYMENT_CONFIRMATION -> Set.of("amountVnd", "itemDescription", "paidAt");
                case COURSE_PENDING_REVIEW -> Set.of("courseName", "teacherName", "submittedAt");
                case COURSE_REVIEW_RESULT -> Set.of("courseName", "decision", "rejectionReason", "reviewedAt");
            };
            if (!p.values().keySet().equals(keys)) throw invalid();
            for (var entry : p.values().entrySet()) {
                String value = entry.getValue();
                boolean empty = entry.getKey().equals("rejectionReason") && "APPROVED".equals(p.values().get("decision"));
                int limit = entry.getKey().equals("rejectionReason") ? 4000 : 1000;
                if (value == null || value.length() > limit || (!empty && value.isBlank())
                        || value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) throw invalid();
            }
            if (p.template() == MailTemplate.PAYMENT_CONFIRMATION) {
                String amount = p.values().get("amountVnd");
                if (!amount.matches("0|[1-9][0-9]{0,18}") || Long.parseLong(amount) < 0) throw invalid();
                Instant.parse(p.values().get("paidAt"));
            }
            if (p.template() == MailTemplate.COURSE_PENDING_REVIEW) Instant.parse(p.values().get("submittedAt"));
            if (p.template() == MailTemplate.COURSE_REVIEW_RESULT) {
                if (!Set.of("APPROVED", "REJECTED").contains(p.values().get("decision"))) throw invalid();
                Instant.parse(p.values().get("reviewedAt"));
            }
        } catch (RuntimeException ignored) { throw invalid(); }
    }

    private static void text(String value, int limit, boolean empty) {
        if (value == null || value.length() > limit || (!empty && value.isBlank())
                || value.chars().anyMatch(c -> Character.isISOControl(c))) throw invalid();
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid mail payload"); }
}
