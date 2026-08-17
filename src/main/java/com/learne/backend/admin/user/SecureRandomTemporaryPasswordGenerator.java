package com.learne.backend.admin.user;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
class SecureRandomTemporaryPasswordGenerator implements TemporaryPasswordGenerator {

    private static final char[] CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final int PASSWORD_LENGTH = 14;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generate() {
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int index = 0; index < PASSWORD_LENGTH; index++) {
            password.append(CHARACTERS[secureRandom.nextInt(CHARACTERS.length)]);
        }
        return password.toString();
    }
}
