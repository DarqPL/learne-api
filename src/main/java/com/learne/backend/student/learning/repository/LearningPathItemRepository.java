package com.learne.backend.student.learning.repository;

import com.learne.backend.student.learning.entity.LearningPath;
import com.learne.backend.student.learning.entity.LearningPathItem;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningPathItemRepository extends JpaRepository<LearningPathItem, Long> {
    @EntityGraph(attributePaths = {"lesson", "lesson.topic", "lesson.topic.course", "lesson.topic.course.teacher"})
    List<LearningPathItem> findByPathOrderBySequenceOrderAsc(LearningPath path);
}
