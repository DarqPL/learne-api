package com.learne.backend.teacher.content.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.entity.Course;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, Long> {
    List<Course> findByTeacherOrderByOrderIndexAscIdAsc(User teacher);

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
}
