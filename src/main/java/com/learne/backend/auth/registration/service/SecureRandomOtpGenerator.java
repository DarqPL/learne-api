package com.learne.backend.auth.registration.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class SecureRandomOtpGenerator implements OtpGenerator {
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generate() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }
}
