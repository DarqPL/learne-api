package com.learne.backend.teacher.content.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.repository.CourseCatalogRow;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.entity.Course;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, Long> {
    List<Course> findByTeacherOrderByOrderIndexAscIdAsc(User teacher);

    long countByTeacher(User teacher);

    long countByTeacherAndStatus(User teacher, ContentStatus status);

    long countByStatus(ContentStatus status);

    List<Course> findTop5ByTeacherOrderByUpdatedAtDescIdDesc(User teacher);

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
            select course.id as id,
                   course.name as name,
                   course.description as description,
                   course.type as type,
                   course.level as level,
                   teacher.id as teacherId,
                   teacher.fullName as teacherFullName,
                   teacher.email as teacherEmail,
                   teacher.avatarUrl as teacherAvatarUrl,
                   count(distinct case when lesson.status = com.learne.backend.teacher.content.enums.ContentStatus.APPROVED then topic.id end) as approvedTopicCount,
                   count(distinct case when lesson.status = com.learne.backend.teacher.content.enums.ContentStatus.APPROVED then lesson.id end) as approvedLessonCount,
                   count(distinct case when question.status = com.learne.backend.teacher.content.enums.ContentStatus.APPROVED then question.id end) as approvedQuestionCount,
                   course.publishedAt as publishedAt,
                   course.pricingType as pricingType,
                   course.priceAmountVnd as priceAmountVnd
            from Course course
            join course.teacher teacher
            left join course.topics topic
            left join topic.lessons lesson
            left join lesson.questions question
            where course.status = :status
              and (:level is null or course.level = :level)
              and (:teacherId is null or teacher.id = :teacherId)
            group by course.id, course.name, course.description, course.type, course.level,
                     teacher.id, teacher.fullName, teacher.email, teacher.avatarUrl,
                     course.publishedAt, course.pricingType, course.priceAmountVnd
            order by course.publishedAt desc, course.id desc
            """)
    List<CourseCatalogRow> findStudentCatalogRows(@Param("status") ContentStatus status,
                                                  @Param("level") String level,
                                                  @Param("teacherId") Long teacherId);

    @Query("""
            select distinct c from Course c
            left join fetch c.teacher
            where c.id = :id and c.status = :status
            """)
    Optional<Course> findStudentDetailByIdAndStatus(@Param("id") Long id, @Param("status") ContentStatus status);

    @Query("""
            select distinct c from Course c
            left join fetch c.teacher
            left join c.topics t
            left join t.lessons l
            where c.status = :status
            order by c.publishedAt desc, c.id desc
            """)
    List<Course> findApprovedCourseRecommendationCandidates(@Param("status") ContentStatus status);
}
