package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.entity.CourseRecommendationPath;
import com.learne.backend.student.learning.enums.LearningPathStatus;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRecommendationPathRepository extends JpaRepository<CourseRecommendationPath, Long> {
    List<CourseRecommendationPath> findByUserAndStatus(User user, LearningPathStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select path from CourseRecommendationPath path where path.user = :user and path.status = :status")
    List<CourseRecommendationPath> findByUserAndStatusForUpdate(@Param("user") User user, @Param("status") LearningPathStatus status);

    Optional<CourseRecommendationPath> findFirstByUserAndStatusOrderByGeneratedAtDesc(User user, LearningPathStatus status);
}
