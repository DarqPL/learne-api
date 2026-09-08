package com.learne.backend.ai.tutor.ai;

import java.util.List;

public record AiTutorRequest(
        String practiceType,
        String title,
        String latestMessage,
        List<MessagePayload> recentMessages,
        ConstraintPayload constraints) {
    public static AiTutorRequest create(String practiceType, String title, String latestMessage, List<MessagePayload> recentMessages) {
        return new AiTutorRequest(practiceType, title, latestMessage, recentMessages,
                new ConstraintPayload(4000, 1, "GENTLE"));
    }

    public record MessagePayload(String senderType, String message) {
    }

    public record ConstraintPayload(int maxReplyLength, int maxFeedbackItems, String feedbackStyle) {
    }
}
