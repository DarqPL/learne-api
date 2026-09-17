package com.learne.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;

class OtpRepositoryLockingTests {

    @Test
    void registrationOtpVerificationLookupUsesPessimisticWriteLock() throws Exception {
        Method method = RegistrationOtpRepository.class.getMethod("findByEmailForUpdate", String.class);

        assertThat(method.getAnnotation(Lock.class))
                .extracting(Lock::value)
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void passwordResetConfirmationLookupUsesPessimisticWriteLock() throws Exception {
        Method method = PasswordResetTokenRepository.class.getMethod("findLatestUnusedByUserForUpdate", User.class, Pageable.class);

        assertThat(method.getAnnotation(Lock.class))
                .extracting(Lock::value)
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }
}
