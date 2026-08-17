package com.learne.backend.admin.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

record CreateTeacherRequest(
        @NotBlank @Email String email,
        @NotBlank String fullName
) {
    CreateTeacherRequest {
        if (email != null) {
            email = email.trim();
        }
        if (fullName != null) {
            fullName = fullName.trim();
        }
    }
}
