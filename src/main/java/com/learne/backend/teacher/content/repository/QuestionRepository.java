package com.learne.backend.teacher.content.repository;

import com.learne.backend.teacher.content.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepository extends JpaRepository<Question, Long> {
}
