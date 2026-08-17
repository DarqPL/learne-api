package com.learne.backend.domain.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    Optional<RefreshToken> findByUserAndRevokedFalse(User user);

    List<RefreshToken> findAllByUserAndRevokedFalse(User user);

    long deleteByExpiresAtBeforeOrRevokedTrue(OffsetDateTime now);
}
