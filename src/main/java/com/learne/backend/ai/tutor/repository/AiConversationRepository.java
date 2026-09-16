package com.learne.backend.ai.tutor.repository;

import com.learne.backend.ai.tutor.entity.AiConversation;
import com.learne.backend.ai.tutor.enums.AiConversationStatus;
import com.learne.backend.domain.auth.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {
    Page<AiConversation> findByUserAndStatusOrderByLastMessageAtDesc(
            User user, AiConversationStatus status, Pageable pageable);

    List<AiConversation> findByUserOrderByLastMessageAtDescIdDesc(User user, Pageable pageable);

    Optional<AiConversation> findByIdAndUserAndStatus(Long id, User user, AiConversationStatus status);

    List<AiConversation> findByStatusAndArchiveAfterBefore(AiConversationStatus status, Instant now);

    List<AiConversation> findByStatusAndDeleteAfterBefore(AiConversationStatus status, Instant now);
}
