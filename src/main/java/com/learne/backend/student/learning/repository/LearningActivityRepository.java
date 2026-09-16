package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.enums.LearningActivitySourceType;
import com.learne.backend.student.learning.entity.LearningActivity;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningActivityRepository extends JpaRepository<LearningActivity, Long> {
    boolean existsByUserAndActivityDate(User user, LocalDate activityDate);
    boolean existsByUserAndSourceTypeAndSourceIdAndActivityDate(User user, LearningActivitySourceType sourceType, Long sourceId, LocalDate activityDate);

    @Query("""
            select activity from LearningActivity activity
            where activity.user = :user
            order by activity.createdAt desc, activity.id desc
            """)
    List<LearningActivity> findRecentByUser(@Param("user") User user, Pageable pageable);
}
