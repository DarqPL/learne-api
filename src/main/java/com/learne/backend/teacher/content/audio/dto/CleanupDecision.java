package com.learne.backend.teacher.content.audio.dto;

public record CleanupDecision(Kind kind, String reason) {
    public enum Kind { DELETE, RETAIN, RETRY }
    public CleanupDecision {
        if (kind == null || reason == null || !reason.matches("[A-Z0-9_]{1,64}"))
            throw new IllegalArgumentException("Invalid cleanup decision");
    }
}
