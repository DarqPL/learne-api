package com.learne.backend.student.learning.repository;

import com.learne.backend.student.learning.entity.PlacementAttempt;
import com.learne.backend.student.learning.entity.PlacementAttemptQuestion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlacementAttemptQuestionRepository extends JpaRepository<PlacementAttemptQuestion, Long> {
    List<PlacementAttemptQuestion> findByPlacementAttemptOrderBySequenceOrderAsc(PlacementAttempt placementAttempt);
}
