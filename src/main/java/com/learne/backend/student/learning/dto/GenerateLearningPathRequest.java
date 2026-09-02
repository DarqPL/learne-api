package com.learne.backend.student.learning.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record GenerateLearningPathRequest(Long teacherId, @Min(1) @Max(12) Integer maxItems) {
}
