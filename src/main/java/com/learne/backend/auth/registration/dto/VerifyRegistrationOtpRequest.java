package com.learne.backend.auth.registration.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyRegistrationOtpRequest(
        @Email @NotBlank String email,
        @Pattern(regexp = "\\d{6}", message = "OTP must contain 6 digits.") String otp
) {
}
