package com.learne.backend.teacher.content;

import org.springframework.data.jpa.repository.JpaRepository;

interface QuestionRepository extends JpaRepository<Question, Long> {
}
