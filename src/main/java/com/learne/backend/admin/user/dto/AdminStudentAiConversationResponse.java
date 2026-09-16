package com.learne.backend.admin.user.dto;

import com.learne.backend.ai.tutor.enums.AiTutorPracticeType;
import java.time.Instant;

public record AdminStudentAiConversationResponse(
        Long id,
        String title,
        AiTutorPracticeType practiceType,
        Instant updatedAt) {
}
