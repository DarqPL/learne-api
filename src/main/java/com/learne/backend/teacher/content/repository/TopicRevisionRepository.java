package com.learne.backend.teacher.content.repository;

import com.learne.backend.teacher.content.entity.TopicRevision;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TopicRevisionRepository extends JpaRepository<TopicRevision, Long> {
    @Query("""
            select tr from TopicRevision tr
            join tr.courseRevision cr
            join cr.course c
            where tr.id = :topicId
              and c.teacher.id = :teacherId
              and cr.teacher.id = :teacherId
              and (c.publishedAt is not null or c.status = com.learne.backend.teacher.content.enums.ContentStatus.APPROVED)
              and cr.status in (com.learne.backend.teacher.content.enums.CourseRevisionStatus.DRAFT,
                                com.learne.backend.teacher.content.enums.CourseRevisionStatus.REJECTED)
              and not exists (
                  select 1 from CourseRevision newer
                  where newer.course = c
                    and newer.revisionNumber > cr.revisionNumber
              )
            """)
    Optional<TopicRevision> findEditableLatestForTeacher(@Param("teacherId") Long teacherId,
                                                         @Param("topicId") Long topicId);
}
