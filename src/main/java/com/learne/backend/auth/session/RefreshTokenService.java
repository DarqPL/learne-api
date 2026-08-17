package com.learne.backend.auth.session;

import com.learne.backend.domain.auth.RefreshToken;
import com.learne.backend.domain.auth.RefreshTokenRepository;
import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final TokenHashService tokenHashService;
    private final AuthProperties authProperties;

    RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenGenerator refreshTokenGenerator,
            TokenHashService tokenHashService,
            AuthProperties authProperties
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.tokenHashService = tokenHashService;
        this.authProperties = authProperties;
    }

    @Transactional
    IssuedRefreshToken issue(User user, String ipAddress, String userAgent) {
        String rawToken = refreshTokenGenerator.generate();
        RefreshToken refreshToken = buildToken(user, rawToken, ipAddress, userAgent);
        refreshTokenRepository.save(refreshToken);
        return new IssuedRefreshToken(rawToken, refreshToken);
    }

    @Transactional(noRollbackFor = ApiException.class)
    IssuedRefreshToken rotate(String rawToken, String ipAddress, String userAgent) {
        RefreshToken currentToken = findExistingToken(rawToken);
        if (currentToken.isRevoked()) {
            revokeAllActiveTokens(currentToken.getUser());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token is invalid.");
        }
        if (currentToken.getExpiresAt().isBefore(OffsetDateTime.now())) {
            revoke(currentToken);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token is invalid.");
        }
        if (!currentToken.getUser().isActive() || currentToken.getUser().isLocked()) {
            revokeAllActiveTokens(currentToken.getUser());
            throw new ApiException(HttpStatus.FORBIDDEN, "Account is not allowed to sign in.");
        }

        String newRawToken = refreshTokenGenerator.generate();
        RefreshToken replacementToken = refreshTokenRepository.save(buildToken(currentToken.getUser(), newRawToken, ipAddress, userAgent));
        revoke(currentToken);
        currentToken.setReplacedByToken(replacementToken);
        refreshTokenRepository.save(currentToken);
        return new IssuedRefreshToken(newRawToken, replacementToken);
    }

    @Transactional
    void revokeIfPresent(String rawToken) {
        refreshTokenRepository.findByTokenHash(tokenHashService.sha256(rawToken)).ifPresent(this::revoke);
    }

    private RefreshToken findExistingToken(String rawToken) {
        return refreshTokenRepository.findByTokenHash(tokenHashService.sha256(rawToken))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token is invalid."));
    }

    private RefreshToken buildToken(User user, String rawToken, String ipAddress, String userAgent) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(tokenHashService.sha256(rawToken));
        refreshToken.setExpiresAt(OffsetDateTime.now().plus(authProperties.refreshTokenTtl()));
        refreshToken.setIpAddress(ipAddress);
        refreshToken.setUserAgent(userAgent);
        refreshToken.setCreatedAt(OffsetDateTime.now());
        return refreshToken;
    }

    private void revokeAllActiveTokens(User user) {
        refreshTokenRepository.findAllByUserAndRevokedFalse(user).forEach(this::revoke);
    }

    private void revoke(RefreshToken refreshToken) {
        if (!refreshToken.isRevoked()) {
            refreshToken.setRevoked(true);
            refreshToken.setRevokedAt(OffsetDateTime.now());
            refreshTokenRepository.save(refreshToken);
        }
    }

    record IssuedRefreshToken(String rawToken, RefreshToken entity) {
    }
}
