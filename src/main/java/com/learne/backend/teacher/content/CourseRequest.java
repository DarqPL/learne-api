package com.learne.backend.teacher.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record CourseRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        @NotNull CourseType type,
        @NotBlank @Size(max = 50) String level,
        Integer orderIndex
) {
    CourseRequest {
        if (name != null) name = name.trim();
        if (description != null) description = description.trim();
        if (level != null) level = level.trim();
    }
}
