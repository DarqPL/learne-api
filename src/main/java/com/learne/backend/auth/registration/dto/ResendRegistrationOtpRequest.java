package com.learne.backend.auth.registration.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ResendRegistrationOtpRequest(
        @Email @NotBlank String email
) {
}
