package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.entity.PlacementAttempt;
import com.learne.backend.student.learning.enums.PlacementAttemptStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlacementAttemptRepository extends JpaRepository<PlacementAttempt, Long> {
    Optional<PlacementAttempt> findFirstByUserAndStatusOrderByStartedAtDesc(User user, PlacementAttemptStatus status);

    Optional<PlacementAttempt> findByIdAndUser(Long id, User user);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select attempt from PlacementAttempt attempt where attempt.id = :id and attempt.user = :user")
    Optional<PlacementAttempt> findByIdAndUserForUpdate(@Param("id") Long id, @Param("user") User user);

    List<PlacementAttempt> findByUserAndStatus(User user, PlacementAttemptStatus status);
}
