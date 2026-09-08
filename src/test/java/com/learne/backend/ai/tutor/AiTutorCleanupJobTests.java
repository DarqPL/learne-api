package com.learne.backend.ai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learne.backend.ai.tutor.entity.AiConversation;
import com.learne.backend.ai.tutor.enums.AiConversationStatus;
import com.learne.backend.ai.tutor.repository.AiConversationRepository;
import com.learne.backend.ai.tutor.service.AiTutorCleanupJob;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiTutorCleanupJobTests {
    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private AiConversationRepository conversationRepository;

    @Test
    void archiveInactiveConversationsArchivesActiveConversationsPastArchiveTime() {
        AiConversation conversation = new AiConversation();
        conversation.setStatus(AiConversationStatus.ACTIVE);
        when(conversationRepository.findByStatusAndArchiveAfterBefore(AiConversationStatus.ACTIVE, NOW))
                .thenReturn(List.of(conversation));

        job().archiveInactiveConversations();

        assertThat(conversation.getStatus()).isEqualTo(AiConversationStatus.ARCHIVED);
        assertThat(conversation.getArchivedAt()).isEqualTo(NOW);
        assertThat(conversation.getDeleteAfter()).isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
        verify(conversationRepository).saveAll(List.of(conversation));
    }

    @Test
    void hardDeleteArchivedConversationsDeletesArchivedConversationsPastDeleteTime() {
        AiConversation conversation = new AiConversation();
        conversation.setStatus(AiConversationStatus.ARCHIVED);
        when(conversationRepository.findByStatusAndDeleteAfterBefore(AiConversationStatus.ARCHIVED, NOW))
                .thenReturn(List.of(conversation));

        job().hardDeleteArchivedConversations();

        verify(conversationRepository).deleteAll(List.of(conversation));
    }

    private AiTutorCleanupJob job() {
        return new AiTutorCleanupJob(conversationRepository, CLOCK);
    }
}
