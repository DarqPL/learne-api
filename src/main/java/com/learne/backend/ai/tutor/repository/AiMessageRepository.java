package com.learne.backend.ai.tutor.repository;

import com.learne.backend.ai.tutor.entity.AiConversation;
import com.learne.backend.ai.tutor.entity.AiMessage;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {
    List<AiMessage> findByConversationOrderByCreatedAtAsc(AiConversation conversation);
    List<AiMessage> findByConversationOrderByCreatedAtDesc(AiConversation conversation, Pageable pageable);
}
