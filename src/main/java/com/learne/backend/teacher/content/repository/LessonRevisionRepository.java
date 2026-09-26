package com.learne.backend.teacher.content.repository;

import com.learne.backend.teacher.content.entity.LessonRevision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LessonRevisionRepository extends JpaRepository<LessonRevision, Long> {
}
