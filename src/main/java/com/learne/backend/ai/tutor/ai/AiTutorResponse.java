package com.learne.backend.ai.tutor.ai;

public record AiTutorResponse(String replyText, FeedbackPayload feedback) {
    public record FeedbackPayload(String errorType, String originalText, String correctedText, String explanation) {
    }
}
