package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.enums.EnrollmentStatus;
import com.learne.backend.teacher.content.entity.Course;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseEnrollmentRepository extends JpaRepository<CourseEnrollment, Long> {
    Optional<CourseEnrollment> findByUserAndCourse(User user, Course course);
    Optional<CourseEnrollment> findByIdAndUser(Long id, User user);
    List<CourseEnrollment> findByUserOrderByEnrolledAtDescIdDesc(User user);
    boolean existsByUserAndCourse(User user, Course course);

    @Query("""
            select enrollment from CourseEnrollment enrollment
            join fetch enrollment.course
            where enrollment.id = :id and enrollment.user = :user
            """)
    Optional<CourseEnrollment> findByIdAndUserWithCourse(@Param("id") Long id, @Param("user") User user);

    @Query("""
            select enrollment from CourseEnrollment enrollment
            join fetch enrollment.course
            where enrollment.id = :id and enrollment.user = :user and enrollment.status in :statuses
            """)
    Optional<CourseEnrollment> findByIdAndUserAndStatusInWithCourse(
            @Param("id") Long id,
            @Param("user") User user,
            @Param("statuses") Collection<EnrollmentStatus> statuses);

    boolean existsByUserAndCourseAndStatusIn(User user, Course course, Collection<EnrollmentStatus> statuses);

    @Query("""
            select distinct enrollment from CourseEnrollment enrollment
            join fetch enrollment.course course
            join fetch course.teacher
            left join fetch enrollment.lessonProgress progress
            left join fetch progress.lesson lesson
            left join fetch lesson.topic
            where enrollment.user = :user
            order by enrollment.enrolledAt desc, enrollment.id desc
            """)
    List<CourseEnrollment> findByUserWithDetailsOrderByEnrolledAtDescIdDesc(@Param("user") User user);
}
