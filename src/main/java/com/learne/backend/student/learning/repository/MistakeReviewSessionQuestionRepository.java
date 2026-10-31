package com.learne.backend.student.learning.repository;

import com.learne.backend.student.learning.entity.MistakeReviewSession;
import com.learne.backend.student.learning.entity.MistakeReviewSessionQuestion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MistakeReviewSessionQuestionRepository extends JpaRepository<MistakeReviewSessionQuestion, Long> {
    List<MistakeReviewSessionQuestion> findBySessionOrderByQuestionOrderAsc(MistakeReviewSession session);
}
