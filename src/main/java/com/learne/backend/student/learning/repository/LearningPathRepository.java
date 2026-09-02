package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.entity.LearningPath;
import com.learne.backend.student.learning.enums.LearningPathStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningPathRepository extends JpaRepository<LearningPath, Long> {
    List<LearningPath> findByUserAndStatus(User user, LearningPathStatus status);

    Optional<LearningPath> findFirstByUserAndStatusOrderByGeneratedAtDesc(User user, LearningPathStatus status);
}
