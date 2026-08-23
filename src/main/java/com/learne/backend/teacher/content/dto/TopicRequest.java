package com.learne.backend.teacher.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TopicRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        Integer orderIndex
) {
    public TopicRequest {
        if (name != null) name = name.trim();
        if (description != null) description = description.trim();
    }
}
