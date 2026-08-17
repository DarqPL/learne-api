package com.learne.backend.auth.registration;

import com.learne.backend.auth.registration.dto.RequestRegistrationOtpRequest;
import com.learne.backend.auth.registration.dto.ResendRegistrationOtpRequest;
import com.learne.backend.auth.registration.dto.VerifyRegistrationOtpRequest;
import com.learne.backend.domain.auth.RegistrationOtp;
import com.learne.backend.domain.auth.RegistrationOtpRepository;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentRegistrationService {
    private final RegistrationOtpRepository registrationOtpRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpGenerator otpGenerator;
    private final RegistrationMailService registrationMailService;

    public StudentRegistrationService(
            RegistrationOtpRepository registrationOtpRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            OtpGenerator otpGenerator,
            RegistrationMailService registrationMailService
    ) {
        this.registrationOtpRepository = registrationOtpRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.otpGenerator = otpGenerator;
        this.registrationMailService = registrationMailService;
    }

    @Transactional
    public void requestOtp(RequestRegistrationOtpRequest request) {
        String email = normalizeEmail(request.email());
        validatePassword(request.password(), request.confirmPassword());
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Email is already registered.");
        }

        registrationOtpRepository.findByEmail(email).ifPresent(registrationOtpRepository::delete);

        String otp = otpGenerator.generate();
        RegistrationOtp pending = new RegistrationOtp();
        pending.setEmail(email);
        pending.setFullName(request.fullName().trim());
        pending.setPasswordHash(passwordEncoder.encode(request.password()));
        pending.setOtpHash(passwordEncoder.encode(otp));
        pending.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        pending.setAttempts(0);
        registrationOtpRepository.save(pending);

        registrationMailService.sendRegistrationOtp(email, otp);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void verifyOtp(VerifyRegistrationOtpRequest request) {
        String email = normalizeEmail(request.email());
        RegistrationOtp pending = registrationOtpRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "No pending registration found for this email."));

        if (pending.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP has expired.");
        }

        if (!passwordEncoder.matches(request.otp(), pending.getOtpHash())) {
            pending.setAttempts(pending.getAttempts() + 1);
            registrationOtpRepository.save(pending);
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP is invalid.");
        }

        if (userRepository.findByEmail(email).isPresent()) {
            registrationOtpRepository.delete(pending);
            throw new ApiException(HttpStatus.CONFLICT, "Email is already registered.");
        }

        User student = new User();
        student.setEmail(email);
        student.setFullName(pending.getFullName());
        student.setPasswordHash(pending.getPasswordHash());
        student.setRole(UserRole.STUDENT);
        student.setActive(true);
        student.setLocked(false);
        student.setMustChangePassword(false);
        userRepository.save(student);
        registrationOtpRepository.delete(pending);
    }

    @Transactional
    public void resendOtp(ResendRegistrationOtpRequest request) {
        String email = normalizeEmail(request.email());
        RegistrationOtp pending = registrationOtpRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "No pending registration found for this email."));

        if (pending.getExpiresAt().isAfter(OffsetDateTime.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Current OTP is still valid. Please wait until it expires before requesting a new one.");
        }

        String otp = otpGenerator.generate();
        pending.setOtpHash(passwordEncoder.encode(otp));
        pending.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        pending.setAttempts(0);
        registrationOtpRepository.save(pending);
        registrationMailService.sendRegistrationOtp(email, otp);
    }

    private void validatePassword(String password, String confirmPassword) {
        if (!password.equals(confirmPassword)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password confirmation does not match.");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (password.length() < 8 || !hasLetter || !hasDigit) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters and contain both letters and numbers.");
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }
}
