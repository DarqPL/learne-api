package com.learne.backend.admin.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateTeacherRequest(
        @NotBlank @Email String email,
        @NotBlank String fullName
) {
    public CreateTeacherRequest {
        if (email != null) {
            email = email.trim();
        }
        if (fullName != null) {
            fullName = fullName.trim();
        }
    }
}
