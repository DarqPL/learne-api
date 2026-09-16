package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.entity.LessonProgress;
import com.learne.backend.student.learning.enums.LessonProgressStatus;
import com.learne.backend.teacher.content.entity.Lesson;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LessonProgressRepository extends JpaRepository<LessonProgress, Long> {
    List<LessonProgress> findByCourseEnrollment(CourseEnrollment courseEnrollment);
    Optional<LessonProgress> findByCourseEnrollmentAndLesson(CourseEnrollment courseEnrollment, Lesson lesson);
    long countByUserAndStatus(User user, LessonProgressStatus status);

    @Query("""
            select progress from LessonProgress progress
            join progress.lesson lesson
            join lesson.topic topic
            where progress.courseEnrollment = :courseEnrollment
            order by topic.orderIndex asc, topic.id asc, lesson.orderIndex asc, lesson.id asc
            """)
    List<LessonProgress> findByCourseEnrollmentOrderedForLearning(@Param("courseEnrollment") CourseEnrollment courseEnrollment);
}
