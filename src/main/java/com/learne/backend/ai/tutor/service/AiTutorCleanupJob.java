package com.learne.backend.ai.tutor.service;

import com.learne.backend.ai.tutor.entity.AiConversation;
import com.learne.backend.ai.tutor.enums.AiConversationStatus;
import com.learne.backend.ai.tutor.repository.AiConversationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AiTutorCleanupJob {
    private static final Duration HARD_DELETE_AFTER_ARCHIVE = Duration.ofDays(30);

    private final AiConversationRepository conversationRepository;
    private final Clock clock;

    public AiTutorCleanupJob(AiConversationRepository conversationRepository, Clock clock) {
        this.conversationRepository = conversationRepository;
        this.clock = clock;
    }

    @Transactional
    @Scheduled(cron = "0 0 3 * * *")
    public void archiveInactiveConversations() {
        Instant now = Instant.now(clock);
        List<AiConversation> conversations = conversationRepository.findByStatusAndArchiveAfterBefore(
                AiConversationStatus.ACTIVE, now);
        conversations.forEach(conversation -> {
            conversation.setStatus(AiConversationStatus.ARCHIVED);
            conversation.setArchivedAt(now);
            conversation.setDeleteAfter(now.plus(HARD_DELETE_AFTER_ARCHIVE));
        });
        conversationRepository.saveAll(conversations);
    }

    @Transactional
    @Scheduled(cron = "0 30 3 * * *")
    public void hardDeleteArchivedConversations() {
        Instant now = Instant.now(clock);
        conversationRepository.deleteAll(conversationRepository.findByStatusAndDeleteAfterBefore(
                AiConversationStatus.ARCHIVED, now));
    }
}
