package com.learne.backend.student.learning.repository;

import com.learne.backend.student.learning.entity.CourseRecommendationItem;
import com.learne.backend.student.learning.entity.CourseRecommendationPath;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRecommendationItemRepository extends JpaRepository<CourseRecommendationItem, Long> {
    List<CourseRecommendationItem> findByPathOrderBySequenceOrderAsc(CourseRecommendationPath path);
}
