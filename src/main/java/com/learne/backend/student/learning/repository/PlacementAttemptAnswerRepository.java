package com.learne.backend.student.learning.repository;

import com.learne.backend.student.learning.entity.PlacementAttempt;
import com.learne.backend.student.learning.entity.PlacementAttemptAnswer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlacementAttemptAnswerRepository extends JpaRepository<PlacementAttemptAnswer, Long> {
    List<PlacementAttemptAnswer> findByPlacementAttempt(PlacementAttempt placementAttempt);
}
