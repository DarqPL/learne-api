package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.enums.LearningActivitySourceType;
import com.learne.backend.student.learning.entity.LearningActivity;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningActivityRepository extends JpaRepository<LearningActivity, Long> {
    boolean existsByUserAndActivityDate(User user, LocalDate activityDate);
    boolean existsByUserAndSourceTypeAndSourceIdAndActivityDate(User user, LearningActivitySourceType sourceType, Long sourceId, LocalDate activityDate);
}
