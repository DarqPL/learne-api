package com.learne.backend.teacher.profile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record UpdateTeacherProfileRequest(
        @NotBlank @Size(max = 2000) String bio,
        @NotBlank @Size(max = 255) String expertise,
        @NotNull @Min(0) @Max(80) Integer yearsOfExperience,
        @Size(max = 1024) String avatarUrl,
        @Size(max = 1024) String introductionVideoUrl
) {
    UpdateTeacherProfileRequest {
        if (bio != null) {
            bio = bio.trim();
        }
        if (expertise != null) {
            expertise = expertise.trim();
        }
        if (avatarUrl != null) {
            avatarUrl = avatarUrl.trim().isEmpty() ? null : avatarUrl.trim();
        }
        if (introductionVideoUrl != null) {
            introductionVideoUrl = introductionVideoUrl.trim().isEmpty() ? null : introductionVideoUrl.trim();
        }
    }
}
