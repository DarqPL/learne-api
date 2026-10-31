package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.entity.MistakeReviewSession;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MistakeReviewSessionRepository extends JpaRepository<MistakeReviewSession, Long> {
    Optional<MistakeReviewSession> findByIdAndUser(Long id, User user);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from MistakeReviewSession session where session.id = :id and session.user = :user")
    Optional<MistakeReviewSession> findByIdAndUserForUpdate(@Param("id") Long id, @Param("user") User user);
}
