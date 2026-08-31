package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.entity.QuizAttempt;
import com.learne.backend.teacher.content.entity.Lesson;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {
    boolean existsByUserAndLessonAndPassedTrue(User user, Lesson lesson);
    Optional<QuizAttempt> findByIdAndUser(Long id, User user);

    @Query("""
            select max(attempt.scorePercent) from QuizAttempt attempt
            where attempt.user = :user and attempt.lesson = :lesson
            """)
    Integer findBestScorePercent(@Param("user") User user, @Param("lesson") Lesson lesson);
}
