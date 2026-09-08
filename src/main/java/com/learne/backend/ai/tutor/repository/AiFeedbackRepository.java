package com.learne.backend.ai.tutor.repository;

import com.learne.backend.ai.tutor.entity.AiFeedback;
import com.learne.backend.ai.tutor.entity.AiMessage;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiFeedbackRepository extends JpaRepository<AiFeedback, Long> {
    Optional<AiFeedback> findByMessage(AiMessage message);
}
