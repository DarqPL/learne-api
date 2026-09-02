package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.entity.QuestionAnswer;
import com.learne.backend.student.learning.entity.QuizAttempt;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionAnswerRepository extends JpaRepository<QuestionAnswer, Long> {
    List<QuestionAnswer> findByQuizAttemptOrderByQuestionOrderIndexAscQuestionIdAsc(QuizAttempt quizAttempt);

    @Query("""
            select answer from QuestionAnswer answer
            join fetch answer.quizAttempt attempt
            join fetch attempt.lesson lesson
            join fetch answer.question question
            where attempt.user = :user
            order by attempt.submittedAt desc, answer.id desc
            """)
    List<QuestionAnswer> findRecentByUser(@Param("user") User user, Pageable pageable);
}
