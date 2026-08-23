package com.learne.backend.teacher.content;

import org.springframework.data.jpa.repository.JpaRepository;

interface LessonRepository extends JpaRepository<Lesson, Long> {
}
