package com.learne.backend.domain.auth;

import com.learne.backend.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PasswordPolicy {

    public void validate(String password, String confirmPassword) {
        if (!password.equals(confirmPassword)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password confirmation does not match.");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (password.length() < 8 || !hasLetter || !hasDigit) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters and contain both letters and numbers.");
        }
    }
}
