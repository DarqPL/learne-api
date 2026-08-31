package com.learne.backend.student.learning.repository;

import com.learne.backend.student.learning.entity.QuestionAnswer;
import com.learne.backend.student.learning.entity.QuizAttempt;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionAnswerRepository extends JpaRepository<QuestionAnswer, Long> {
    List<QuestionAnswer> findByQuizAttemptOrderByQuestionOrderIndexAscQuestionIdAsc(QuizAttempt quizAttempt);
}
