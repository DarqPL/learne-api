package com.learne.backend.teacher.content.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.enums.SkillType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionRepository extends JpaRepository<Question, Long> {
    @Query("""
            select question from Question question
            join fetch question.lesson lesson
            join fetch lesson.topic topic
            join fetch topic.course
            where question.id = :id
            """)
    Optional<Question> findByIdWithLesson(@Param("id") Long id);

    @Query("""
            select distinct question from Question question
            left join fetch question.options options
            where question.lesson = :lesson and question.status = :status
            order by question.orderIndex asc, question.id asc
            """)
    List<Question> findByLessonAndStatusForStudentQuiz(@Param("lesson") Lesson lesson, @Param("status") ContentStatus status);

    @Query("""
            select distinct question from Question question
            left join fetch question.options options
            join question.lesson lesson
            join lesson.topic topic
            join topic.course course
            where question.status = :status
              and lesson.status = :status
              and course.status = :status
              and lesson.placementTest = true
              and course.type = :courseType
              and lesson.skillType = :skillType
            order by question.orderIndex asc, question.id asc
            """)
    List<Question> findApprovedPlacementQuestionsByCourseType(@Param("courseType") CourseType courseType,
            @Param("skillType") SkillType skillType,
            @Param("status") ContentStatus status);

    @Query("""
            select count(question) from Question question
            join question.lesson lesson
            join lesson.topic topic
            join topic.course course
            where course.teacher = :teacher
            """)
    long countByCourseTeacher(@Param("teacher") User teacher);
}
