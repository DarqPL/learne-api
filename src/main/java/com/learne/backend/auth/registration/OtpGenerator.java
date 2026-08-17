package com.learne.backend.auth.registration;

@FunctionalInterface
public interface OtpGenerator {
    String generate();
}
