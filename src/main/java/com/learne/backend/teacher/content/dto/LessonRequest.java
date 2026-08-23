package com.learne.backend.teacher.content.dto;

import com.learne.backend.teacher.content.enums.SkillType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LessonRequest(
        @NotBlank @Size(max = 255) String title,
        String description,
        String content,
        @NotNull SkillType skillType,
        Integer orderIndex,
        @Min(0) Integer xpReward,
        boolean placementTest
) {
    public LessonRequest {
        if (title != null) title = title.trim();
        if (description != null) description = description.trim();
        if (content != null) content = content.trim();
    }
}
