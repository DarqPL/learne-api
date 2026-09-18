package com.learne.backend.domain.auth;

import java.time.OffsetDateTime;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegistrationOtpRepository extends JpaRepository<RegistrationOtp, Long> {
    Optional<RegistrationOtp> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select otp from RegistrationOtp otp where otp.email = :email")
    Optional<RegistrationOtp> findByEmailForUpdate(@Param("email") String email);

    long deleteByExpiresAtBefore(OffsetDateTime now);
}
