package com.learne.backend.teacher.content.repository;

import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRevisionRepository extends JpaRepository<CourseRevision, Long> {
    Optional<CourseRevision> findTopByCourseOrderByRevisionNumberDesc(Course course);

    @Query("""
            select cr from CourseRevision cr
            where cr.course.id in :courseIds
              and not exists (
                  select 1 from CourseRevision newer
                  where newer.course = cr.course
                    and newer.revisionNumber > cr.revisionNumber
              )
            """)
    List<CourseRevision> findLatestByCourseIds(@Param("courseIds") List<Long> courseIds);

    Optional<CourseRevision> findTopByCourseAndStatusOrderByRevisionNumberDesc(Course course, CourseRevisionStatus status);

    List<CourseRevision> findByCourseAndStatus(Course course, CourseRevisionStatus status);

    List<CourseRevision> findByStatusOrderByUpdatedAtDesc(CourseRevisionStatus status);

    @Query("""
            select cr from CourseRevision cr
            join fetch cr.course c
            join fetch c.teacher
            where cr.status = :status
              and not exists (
                  select 1 from CourseRevision newer
                  where newer.course = cr.course
                    and newer.revisionNumber > cr.revisionNumber
              )
            order by cr.updatedAt asc, cr.id asc
            """)
    List<CourseRevision> findLatestByStatusOrderByUpdatedAtAsc(@Param("status") CourseRevisionStatus status);

    Optional<CourseRevision> findTopByCourseIdAndStatusOrderByRevisionNumberDesc(Long courseId, CourseRevisionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CourseRevision> findLockedTopByCourseIdAndStatusOrderByRevisionNumberDesc(Long courseId, CourseRevisionStatus status);

    Optional<CourseRevision> findTopByCourseIdOrderByRevisionNumberDesc(Long courseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select cr from CourseRevision cr
            where cr.course.id = :courseId
            order by cr.revisionNumber desc
            """)
    List<CourseRevision> findLockedLatestByCourseId(@Param("courseId") Long courseId, org.springframework.data.domain.Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select cr from CourseRevision cr where cr.id = :id")
    Optional<CourseRevision> findLockedById(@Param("id") Long id);
}
