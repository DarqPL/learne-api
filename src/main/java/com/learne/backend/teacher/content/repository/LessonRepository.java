package com.learne.backend.teacher.content.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.entity.Lesson;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LessonRepository extends JpaRepository<Lesson, Long> {
    boolean existsByAudioPublicId(String publicId);
    boolean existsByAudioUrl(String url);
    // Conservative alias guard: URL-only references may have a different version/extension.
    boolean existsByAudioUrlContaining(String publicId);

    @Query("select distinct l.audioUrl from Lesson l where l.audioUrl is not null")
    List<String> findAllAudioUrls();

    @Query("select l.topic.course.id from Lesson l where l.id = :id and l.teacher.id = :teacherId")
    Optional<Long> findCourseIdByLessonIdAndTeacherId(@Param("id") Long id, @Param("teacherId") Long teacherId);

    @Query("""
            select lesson from Lesson lesson
            join fetch lesson.topic topic
            join fetch topic.course
            where lesson.id = :id
            """)
    Optional<Lesson> findByIdWithTopicAndCourse(@Param("id") Long id);

    @Query("""
            select count(lesson) from Lesson lesson
            join lesson.topic topic
            join topic.course course
            where course.teacher = :teacher
            """)
    long countByCourseTeacher(@Param("teacher") User teacher);
}
