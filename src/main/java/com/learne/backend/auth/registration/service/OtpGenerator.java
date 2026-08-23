package com.learne.backend.auth.registration.service;

@FunctionalInterface
public interface OtpGenerator {
    String generate();
}
