package com.learne.backend.auth.registration.controller;

import com.learne.backend.auth.registration.dto.RequestRegistrationOtpRequest;
import com.learne.backend.auth.registration.dto.ResendRegistrationOtpRequest;
import com.learne.backend.auth.registration.dto.VerifyRegistrationOtpRequest;
import com.learne.backend.auth.registration.service.StudentRegistrationService;
import com.learne.backend.shared.api.ApiMessageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/register")
public class AuthRegistrationController {
    private final StudentRegistrationService studentRegistrationService;

    public AuthRegistrationController(StudentRegistrationService studentRegistrationService) {
        this.studentRegistrationService = studentRegistrationService;
    }

    @PostMapping("/request-otp")
    ApiMessageResponse requestOtp(@Valid @RequestBody RequestRegistrationOtpRequest request) {
        studentRegistrationService.requestOtp(request);
        return new ApiMessageResponse("Registration OTP has been sent to your email.");
    }

    @PostMapping("/verify-otp")
    ApiMessageResponse verifyOtp(@Valid @RequestBody VerifyRegistrationOtpRequest request) {
        studentRegistrationService.verifyOtp(request);
        return new ApiMessageResponse("Registration completed successfully.");
    }

    @PostMapping("/resend-otp")
    ApiMessageResponse resendOtp(@Valid @RequestBody ResendRegistrationOtpRequest request) {
        studentRegistrationService.resendOtp(request);
        return new ApiMessageResponse("A new registration OTP has been sent to your email.");
    }
}
