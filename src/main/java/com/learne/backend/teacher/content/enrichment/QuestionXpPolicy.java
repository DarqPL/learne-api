package com.learne.backend.teacher.content.enrichment;

import org.springframework.stereotype.Component;

@Component
public class QuestionXpPolicy {
    public int xpFor(String difficulty) {
        return switch (difficulty) {
            case "easy" -> 10;
            case "medium" -> 20;
            case "hard" -> 30;
            default -> throw new IllegalArgumentException("Unsupported difficulty: " + difficulty);
        };
    }
}
