package com.learne.backend.auth.session.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ConfirmPasswordResetRequest(
        @NotBlank @Email String email,
        @NotBlank String token,
        @NotBlank String newPassword,
        @NotBlank String confirmNewPassword
) {
}
