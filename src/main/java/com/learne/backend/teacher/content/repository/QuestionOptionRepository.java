package com.learne.backend.teacher.content.repository;

import com.learne.backend.teacher.content.entity.QuestionOption;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionOptionRepository extends JpaRepository<QuestionOption, Long> {
}
