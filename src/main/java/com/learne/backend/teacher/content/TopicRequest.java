package com.learne.backend.teacher.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record TopicRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        Integer orderIndex
) {
    TopicRequest {
        if (name != null) name = name.trim();
        if (description != null) description = description.trim();
    }
}
