package com.learne.backend.teacher.content.repository;

import com.learne.backend.teacher.content.entity.QuestionRevision;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionRevisionRepository extends JpaRepository<QuestionRevision, Long> {
    @Query("select q.lessonRevision.topicRevision.courseRevision.course.id from QuestionRevision q where q.id = :id and q.teacher.id = :teacherId")
    Optional<Long> findCourseIdByQuestionIdAndTeacherId(@Param("id") Long id, @Param("teacherId") Long teacherId);

    @Query("""
            select qr from QuestionRevision qr
            join qr.lessonRevision lr
            join lr.topicRevision tr
            join tr.courseRevision cr
            join cr.course c
            where qr.id = :questionId
              and c.teacher.id = :teacherId
              and cr.teacher.id = :teacherId
              and qr.teacher.id = :teacherId
              and (c.publishedAt is not null or c.status = com.learne.backend.teacher.content.enums.ContentStatus.APPROVED)
              and cr.status in (com.learne.backend.teacher.content.enums.CourseRevisionStatus.DRAFT,
                                com.learne.backend.teacher.content.enums.CourseRevisionStatus.REJECTED)
              and not exists (
                  select 1 from CourseRevision newer
                  where newer.course = c
                    and newer.revisionNumber > cr.revisionNumber
              )
            """)
    Optional<QuestionRevision> findEditableLatestForTeacher(@Param("teacherId") Long teacherId,
                                                            @Param("questionId") Long questionId);
}
