package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.entity.UserStats;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserStatsRepository extends JpaRepository<UserStats, Long> {
    Optional<UserStats> findByUser(User user);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select stats from UserStats stats where stats.user = :user")
    Optional<UserStats> findByUserForUpdate(@Param("user") User user);

    @Query("""
            select stats from UserStats stats
            join fetch stats.user user
            where user.role = com.learne.backend.domain.auth.UserRole.STUDENT
              and user.active = true
            order by stats.totalXp desc, stats.currentStreakDays desc, stats.longestStreakDays desc, user.id asc
            """)
    List<UserStats> findTopStudentStats(Pageable pageable);
}
