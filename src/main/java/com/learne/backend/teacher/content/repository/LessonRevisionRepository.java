package com.learne.backend.teacher.content.repository;

import com.learne.backend.teacher.content.entity.LessonRevision;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LessonRevisionRepository extends JpaRepository<LessonRevision, Long> {
    @Query("""
            select lr from LessonRevision lr
            join lr.topicRevision tr
            join tr.courseRevision cr
            join cr.course c
            where lr.id = :lessonId
              and c.teacher.id = :teacherId
              and cr.teacher.id = :teacherId
              and lr.teacher.id = :teacherId
              and (c.publishedAt is not null or c.status = com.learne.backend.teacher.content.enums.ContentStatus.APPROVED)
              and cr.status in (com.learne.backend.teacher.content.enums.CourseRevisionStatus.DRAFT,
                                com.learne.backend.teacher.content.enums.CourseRevisionStatus.REJECTED)
              and not exists (
                  select 1 from CourseRevision newer
                  where newer.course = c
                    and newer.revisionNumber > cr.revisionNumber
              )
            """)
    Optional<LessonRevision> findEditableLatestForTeacher(@Param("teacherId") Long teacherId,
                                                          @Param("lessonId") Long lessonId);
}
