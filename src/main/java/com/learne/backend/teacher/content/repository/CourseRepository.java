package com.learne.backend.teacher.content.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.entity.Course;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, Long> {
    List<Course> findByTeacherOrderByOrderIndexAscIdAsc(User teacher);

    List<Course> findByStatusOrderByUpdatedAtAscIdAsc(ContentStatus status);

    Optional<Course> findByIdAndTeacher(Long id, User teacher);

    @Query("""
            select distinct c from Course c
            left join fetch c.topics t
            left join fetch t.lessons l
            left join fetch l.questions q
            left join fetch q.options
            where c.id = :id and c.teacher = :teacher
            """)
    Optional<Course> findDetailByIdAndTeacher(@Param("id") Long id, @Param("teacher") User teacher);

    @Query("""
            select c from Course c
            left join fetch c.teacher
            left join fetch c.reviewedBy
            where c.id = :id
            """)
    Optional<Course> findReviewDetailById(@Param("id") Long id);

    @Query("""
            select distinct c from Course c
            left join fetch c.teacher
            left join c.topics t
            left join t.lessons l
            left join l.questions q
            where c.status = :status
              and (:level is null or c.level = :level)
              and (:teacherId is null or c.teacher.id = :teacherId)
            order by c.publishedAt desc, c.id desc
            """)
    List<Course> findStudentCatalog(@Param("status") ContentStatus status,
                                     @Param("level") String level,
                                     @Param("teacherId") Long teacherId);

    @Query("""
            select distinct c from Course c
            left join fetch c.teacher
            where c.id = :id and c.status = :status
            """)
    Optional<Course> findStudentDetailByIdAndStatus(@Param("id") Long id, @Param("status") ContentStatus status);
}
