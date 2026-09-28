package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.entity.QuizAttempt;
import com.learne.backend.teacher.content.entity.Lesson;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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

    @Query("""
            select attempt from QuizAttempt attempt
            join fetch attempt.lesson lesson
            where attempt.user = :user
            order by attempt.submittedAt desc, attempt.id desc
            """)
    List<QuizAttempt> findRecentByUser(@Param("user") User user, Pageable pageable);

    @Query("""
            select course.id as courseId,
                   course.name as courseName,
                   course.teacher.id as teacherId,
                   course.teacher.fullName as teacherName,
                   count(attempt) as submittedQuizzes
            from QuizAttempt attempt
            join attempt.lesson lesson
            join lesson.topic topic
            join topic.course course
            where attempt.status = com.learne.backend.student.learning.enums.QuizAttemptStatus.SUBMITTED
              and attempt.submittedAt >= :start
              and attempt.submittedAt < :end
            group by course.id, course.name, course.teacher.id, course.teacher.fullName
            order by course.id asc
            """)
    List<SubmittedQuizAggregate> aggregateSubmittedQuizzes(@Param("start") Instant start, @Param("end") Instant end);

    interface SubmittedQuizAggregate {
        Long getCourseId();
        String getCourseName();
        Long getTeacherId();
        String getTeacherName();
        long getSubmittedQuizzes();
    }
}
