package com.learne.backend.teacher.content.dto;

import com.learne.backend.teacher.content.enums.CoursePricingType;
import com.learne.backend.teacher.content.enums.CourseType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CourseRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        @NotNull CourseType type,
        @NotBlank @Size(max = 50) String level,
        Integer orderIndex,
        CoursePricingType pricingType,
        Long proposedPriceAmountVnd
) {
    public CourseRequest {
        if (name != null) name = name.trim();
        if (description != null) description = description.trim();
        if (level != null) level = level.trim();
    }
}
