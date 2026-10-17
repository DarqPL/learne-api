package com.learne.backend.teacher.content.repository;

import com.learne.backend.teacher.content.entity.Topic;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TopicRepository extends JpaRepository<Topic, Long> {
    @Query("select t.course.id from Topic t where t.id = :id and t.course.teacher.id = :teacherId")
    Optional<Long> findCourseIdByTopicIdAndTeacherId(@Param("id") Long id, @Param("teacherId") Long teacherId);
}
