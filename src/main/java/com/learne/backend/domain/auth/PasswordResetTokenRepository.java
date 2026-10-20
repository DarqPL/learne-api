package com.learne.backend.domain.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    @Query("select token from PasswordResetToken token where token.user = :user and token.usedAt is null order by token.createdAt desc, token.id desc limit 1")
    Optional<PasswordResetToken> findTopByUserAndUsedAtIsNullOrderByCreatedAtDesc(User user);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from PasswordResetToken token where token.user = :user and token.usedAt is null order by token.createdAt desc, token.id desc")
    List<PasswordResetToken> findLatestUnusedByUserForUpdate(@Param("user") User user, Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Query("update PasswordResetToken token set token.usedAt = :usedAt where token.user = :user and token.usedAt is null")
    int markAllUnusedByUserAsUsed(@Param("user") User user, @Param("usedAt") OffsetDateTime usedAt);

    long deleteByExpiresAtBeforeOrUsedAtIsNotNull(OffsetDateTime now);
}
