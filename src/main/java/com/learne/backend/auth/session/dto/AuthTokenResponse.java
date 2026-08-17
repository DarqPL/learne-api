package com.learne.backend.auth.session.dto;

public record AuthTokenResponse(
        String accessToken,
        long expiresInSeconds,
        AuthUserResponse user
) {
}
