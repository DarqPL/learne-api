package com.learne.backend.auth.session.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
class SecureRandomPasswordResetTokenGenerator implements PasswordResetTokenGenerator {

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generate() {
        return "%06d".formatted(secureRandom.nextInt(1_000_000));
    }
}
