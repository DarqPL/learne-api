package com.learne.backend.auth.session;

import com.learne.backend.auth.session.dto.AuthTokenResponse;
import com.learne.backend.auth.session.dto.AuthUserResponse;
import com.learne.backend.auth.session.dto.ChangePasswordRequest;
import com.learne.backend.domain.auth.PasswordPolicy;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthSessionService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    AuthSessionService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            JwtService jwtService,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    LoginResult login(String email, String password, String ipAddress, String userAgent) {
        User user = userRepository.findByEmail(email.toLowerCase().trim())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password."));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }
        assertCanSignIn(user);

        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue(user, ipAddress, userAgent);
        return new LoginResult(tokenResponse(user), refreshToken.rawToken());
    }

    @Transactional(noRollbackFor = ApiException.class)
    LoginResult refresh(String rawRefreshToken, String ipAddress, String userAgent) {
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.rotate(rawRefreshToken, ipAddress, userAgent);
        return new LoginResult(tokenResponse(refreshToken.entity().getUser()), refreshToken.rawToken());
    }

    @Transactional
    void logout(String rawRefreshToken) {
        refreshTokenService.revokeIfPresent(rawRefreshToken);
    }

    @Transactional
    void changePassword(User user, ChangePasswordRequest request) {
        User managedUser = userRepository.findById(user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid access token."));
        if (!passwordEncoder.matches(request.currentPassword(), managedUser.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
        }
        if (passwordEncoder.matches(request.newPassword(), managedUser.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New password must be different from current password.");
        }
        passwordPolicy.validate(request.newPassword(), request.confirmNewPassword());

        managedUser.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        managedUser.setMustChangePassword(false);
        userRepository.save(managedUser);
        refreshTokenService.revokeAllActiveTokens(managedUser);
    }

    AuthTokenResponse tokenResponse(User user) {
        return new AuthTokenResponse(
                jwtService.createAccessToken(user),
                jwtService.accessTokenTtlSeconds(),
                AuthUserResponse.from(user)
        );
    }

    private void assertCanSignIn(User user) {
        if (!user.isActive() || user.isLocked()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Account is not allowed to sign in.");
        }
    }

    record LoginResult(AuthTokenResponse response, String refreshToken) {
    }
}
