package com.learne.backend.domain.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistrationOtpRepository extends JpaRepository<RegistrationOtp, Long> {
    Optional<RegistrationOtp> findByEmail(String email);
}
