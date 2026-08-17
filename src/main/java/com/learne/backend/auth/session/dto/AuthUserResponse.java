package com.learne.backend.auth.session.dto;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRole;

public record AuthUserResponse(
        Long id,
        String email,
        String fullName,
        UserRole role,
        boolean mustChangePassword
) {
    public static AuthUserResponse from(User user) {
        return new AuthUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.isMustChangePassword()
        );
    }
}
