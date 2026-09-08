package com.learne.backend.teacher.content.repository;

import com.learne.backend.teacher.content.entity.Lesson;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LessonRepository extends JpaRepository<Lesson, Long> {
    @Query("""
            select lesson from Lesson lesson
            join fetch lesson.topic topic
            join fetch topic.course
            where lesson.id = :id
            """)
    Optional<Lesson> findByIdWithTopicAndCourse(@Param("id") Long id);
}
