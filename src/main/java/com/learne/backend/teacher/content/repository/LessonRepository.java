package com.learne.backend.teacher.content.repository;

import com.learne.backend.teacher.content.entity.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LessonRepository extends JpaRepository<Lesson, Long> {
}
