package com.learne.backend.ai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learne.backend.ai.tutor.ai.AiTutorClient;
import com.learne.backend.ai.tutor.ai.AiTutorRequest;
import com.learne.backend.ai.tutor.ai.AiTutorResponse;
import com.learne.backend.ai.tutor.dto.AiConversationDetailResponse;
import com.learne.backend.ai.tutor.dto.AiConversationSummaryResponse;
import com.learne.backend.ai.tutor.dto.CreateAiConversationRequest;
import com.learne.backend.ai.tutor.dto.SendAiTutorMessageRequest;
import com.learne.backend.ai.tutor.dto.SendAiTutorMessageResponse;
import com.learne.backend.ai.tutor.entity.AiConversation;
import com.learne.backend.ai.tutor.entity.AiFeedback;
import com.learne.backend.ai.tutor.entity.AiMessage;
import com.learne.backend.ai.tutor.enums.AiConversationStatus;
import com.learne.backend.ai.tutor.enums.AiMessageSenderType;
import com.learne.backend.ai.tutor.enums.AiTutorFeedbackType;
import com.learne.backend.ai.tutor.enums.AiTutorPracticeType;
import com.learne.backend.ai.tutor.repository.AiConversationRepository;
import com.learne.backend.ai.tutor.repository.AiFeedbackRepository;
import com.learne.backend.ai.tutor.repository.AiMessageRepository;
import com.learne.backend.ai.tutor.service.AiTutorService;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AiTutorServiceTests {
    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private AiConversationRepository conversationRepository;
    @Mock private AiMessageRepository messageRepository;
    @Mock private AiFeedbackRepository feedbackRepository;
    @Mock private AiTutorClient aiTutorClient;

    @Test
    void createStoresActiveConversationWithTrimmedTitleAndRetention() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        when(conversationRepository.save(any(AiConversation.class)))
                .thenAnswer(invocation -> assignId(invocation.getArgument(0), 10L));

        AiConversationSummaryResponse response = service().create(
                student, new CreateAiConversationRequest("  Daily speaking  ", AiTutorPracticeType.FREE_CHAT));

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.title()).isEqualTo("Daily speaking");
        assertThat(response.practiceType()).isEqualTo(AiTutorPracticeType.FREE_CHAT);
        assertThat(response.status()).isEqualTo(AiConversationStatus.ACTIVE);
        assertThat(response.lastMessageAt()).isEqualTo(NOW);
        assertThat(response.cleanupWarnAt()).isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
        assertThat(response.archiveAfter()).isEqualTo(NOW.plusSeconds(32L * 24 * 60 * 60));
        assertThat(response.cleanupWarning()).isFalse();
    }

    @Test
    void createRejectsTeacherBeforeSaving() throws Exception {
        User teacher = user(2L, UserRole.TEACHER);

        assertThatThrownBy(() -> service().create(
                teacher, new CreateAiConversationRequest("Practice", AiTutorPracticeType.ROLEPLAY)))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException exception = (ApiException) error;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getMessage()).isEqualTo("AI Tutor is available to students only.");
                });
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void sendMessageStoresUserAiResponseAndFeedback() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        AiConversation conversation = conversation(20L, student, NOW.minusSeconds(60));
        AiMessage previous = message(30L, conversation, AiMessageSenderType.AI, "Welcome", NOW.minusSeconds(30));
        when(conversationRepository.findByIdAndUserAndStatus(20L, student, AiConversationStatus.ACTIVE))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtDesc(eq(conversation), any()))
                .thenReturn(List.of(previous));
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(invocation -> {
            AiMessage message = invocation.getArgument(0);
            assignId(message, message.getSenderType() == AiMessageSenderType.USER ? 31L : 32L);
            return message;
        });
        when(feedbackRepository.save(any(AiFeedback.class))).thenAnswer(invocation -> assignId(invocation.getArgument(0), 40L));
        when(conversationRepository.save(any(AiConversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiTutorClient.generateReply(any())).thenReturn(new AiTutorResponse(
                "Try saying: I went to school.",
                new AiTutorResponse.FeedbackPayload("GRAMMAR", "I go", "I went", "Use past tense.")));

        SendAiTutorMessageResponse response = service().sendMessage(
                student, 20L, new SendAiTutorMessageRequest("  I go to school yesterday.  "));

        assertThat(response.userMessage().id()).isEqualTo(31L);
        assertThat(response.userMessage().message()).isEqualTo("I go to school yesterday.");
        assertThat(response.aiMessage().id()).isEqualTo(32L);
        assertThat(response.aiMessage().message()).isEqualTo("Try saying: I went to school.");
        assertThat(response.userMessage().feedback().id()).isEqualTo(40L);
        assertThat(response.userMessage().feedback().errorType()).isEqualTo("GRAMMAR");
        assertThat(response.aiMessage().feedback()).isNull();
        assertThat(response.conversation().lastMessageAt()).isEqualTo(NOW);
        assertThat(conversation.getCleanupWarnAt()).isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));

        ArgumentCaptor<AiTutorRequest> requestCaptor = ArgumentCaptor.forClass(AiTutorRequest.class);
        verify(aiTutorClient).generateReply(requestCaptor.capture());
        assertThat(requestCaptor.getValue().latestMessage()).isEqualTo("I go to school yesterday.");
        assertThat(requestCaptor.getValue().recentMessages())
                .extracting(AiTutorRequest.MessagePayload::senderType)
                .containsExactly("AI");
    }

    @Test
    void sendMessageRefreshesRetentionAfterAiMessageIsSavedOnSuccess() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        AiConversation conversation = conversation(20L, student, NOW.minusSeconds(60));
        when(conversationRepository.findByIdAndUserAndStatus(20L, student, AiConversationStatus.ACTIVE))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(List.of());
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(invocation -> {
            AiMessage message = invocation.getArgument(0);
            assignId(message, message.getSenderType() == AiMessageSenderType.USER ? 31L : 32L);
            return message;
        });
        when(conversationRepository.save(any(AiConversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiTutorClient.generateReply(any())).thenReturn(new AiTutorResponse("Reply", null));

        SendAiTutorMessageResponse response = service().sendMessage(student, 20L, new SendAiTutorMessageRequest("Hello"));

        InOrder order = inOrder(messageRepository, aiTutorClient, conversationRepository);
        order.verify(messageRepository).save(any(AiMessage.class));
        order.verify(aiTutorClient).generateReply(any());
        order.verify(messageRepository).save(any(AiMessage.class));
        order.verify(conversationRepository).save(conversation);
        assertThat(response.conversation().lastMessageAt()).isEqualTo(NOW);
        assertThat(response.conversation().cleanupWarnAt()).isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
        assertThat(response.conversation().archiveAfter()).isEqualTo(NOW.plusSeconds(32L * 24 * 60 * 60));
    }

    @Test
    void getReturnsActiveOwnerConversationDetailWithMessagesAndFeedbackInChronologicalOrder() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        AiConversation conversation = conversation(20L, student, NOW.minusSeconds(60));
        AiMessage first = message(30L, conversation, AiMessageSenderType.USER, "I go school", NOW.minusSeconds(30));
        first.setFeedback(feedback(40L, first));
        AiMessage second = message(31L, conversation, AiMessageSenderType.AI, "Say: I go to school.", NOW.minusSeconds(20));
        when(conversationRepository.findByIdAndUserAndStatus(20L, student, AiConversationStatus.ACTIVE))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation)).thenReturn(List.of(first, second));

        AiConversationDetailResponse response = service().get(student, 20L);

        verify(conversationRepository).findByIdAndUserAndStatus(20L, student, AiConversationStatus.ACTIVE);
        verify(messageRepository).findByConversationOrderByCreatedAtAsc(conversation);
        assertThat(response.id()).isEqualTo(20L);
        assertThat(response.messages()).extracting("id").containsExactly(30L, 31L);
        assertThat(response.messages().getFirst().feedback().id()).isEqualTo(40L);
        assertThat(response.messages().get(1).feedback()).isNull();
    }

    @Test
    void getThrowsNotFoundWhenActiveOwnerLookupMisses() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        when(conversationRepository.findByIdAndUserAndStatus(99L, student, AiConversationStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(student, 99L))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException exception = (ApiException) error;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("AI Tutor conversation not found.");
                });
        verify(messageRepository, never()).findByConversationOrderByCreatedAtAsc(any());
    }

    @Test
    void sendMessageThrowsNotFoundWhenActiveOwnerLookupMisses() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        when(conversationRepository.findByIdAndUserAndStatus(99L, student, AiConversationStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().sendMessage(student, 99L, new SendAiTutorMessageRequest("Hello")))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(messageRepository, never()).save(any());
        verify(aiTutorClient, never()).generateReply(any());
    }

    @Test
    void sendMessageKeepsUserMessageAndThrowsBadGatewayWhenAiFails() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        AiConversation conversation = conversation(20L, student, NOW.minusSeconds(60));
        when(conversationRepository.findByIdAndUserAndStatus(20L, student, AiConversationStatus.ACTIVE))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(List.of());
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(invocation -> assignId(invocation.getArgument(0), 31L));
        when(conversationRepository.save(any(AiConversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiTutorClient.generateReply(any())).thenThrow(new IllegalStateException("network down"));

        assertThatThrownBy(() -> service().sendMessage(student, 20L, new SendAiTutorMessageRequest("Hello")))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException exception = (ApiException) error;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(exception.getMessage()).isEqualTo("AI Tutor is temporarily unavailable. Please try again later.");
                });
        verify(messageRepository, times(1)).save(any(AiMessage.class));
        verify(feedbackRepository, never()).save(any());
        assertThat(conversation.getLastMessageAt()).isEqualTo(NOW);
    }

    @Test
    void sendMessageDropsInvalidFeedbackWhenReplyIsValid() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        AiConversation conversation = conversation(20L, student, NOW.minusSeconds(60));
        when(conversationRepository.findByIdAndUserAndStatus(20L, student, AiConversationStatus.ACTIVE))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(List.of());
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(invocation -> {
            AiMessage message = invocation.getArgument(0);
            assignId(message, message.getSenderType() == AiMessageSenderType.USER ? 31L : 32L);
            return message;
        });
        when(conversationRepository.save(any(AiConversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiTutorClient.generateReply(any())).thenReturn(new AiTutorResponse(
                "Valid reply", new AiTutorResponse.FeedbackPayload("UNKNOWN", "a", "b", "c")));

        SendAiTutorMessageResponse response = service().sendMessage(student, 20L, new SendAiTutorMessageRequest("Hello"));

        assertThat(response.userMessage().feedback()).isNull();
        assertThat(response.aiMessage().message()).isEqualTo("Valid reply");
        assertThat(response.aiMessage().feedback()).isNull();
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void sendMessageStoresAiMessageWhenFeedbackIsNull() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        AiConversation conversation = conversation(20L, student, NOW.minusSeconds(60));
        when(conversationRepository.findByIdAndUserAndStatus(20L, student, AiConversationStatus.ACTIVE))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(List.of());
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(invocation -> {
            AiMessage message = invocation.getArgument(0);
            assignId(message, message.getSenderType() == AiMessageSenderType.USER ? 31L : 32L);
            return message;
        });
        when(conversationRepository.save(any(AiConversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiTutorClient.generateReply(any())).thenReturn(new AiTutorResponse("Valid reply", null));

        SendAiTutorMessageResponse response = service().sendMessage(student, 20L, new SendAiTutorMessageRequest("Hello"));

        assertThat(response.userMessage().feedback()).isNull();
        assertThat(response.aiMessage().id()).isEqualTo(32L);
        assertThat(response.aiMessage().feedback()).isNull();
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void sendMessageThrowsBadGatewayAndStoresNoAiMessageWhenReplyIsBlank() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        AiConversation conversation = conversation(20L, student, NOW.minusSeconds(60));
        when(conversationRepository.findByIdAndUserAndStatus(20L, student, AiConversationStatus.ACTIVE))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(List.of());
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(invocation -> assignId(invocation.getArgument(0), 31L));
        when(conversationRepository.save(any(AiConversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiTutorClient.generateReply(any())).thenReturn(new AiTutorResponse("   ", null));

        assertThatThrownBy(() -> service().sendMessage(student, 20L, new SendAiTutorMessageRequest("Hello")))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        verify(messageRepository, times(1)).save(any(AiMessage.class));
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void sendMessageSendsRecentHistoryBoundedToTenAndChronological() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        AiConversation conversation = conversation(20L, student, NOW.minusSeconds(60));
        List<AiMessage> newestFirst = List.of(
                message(40L, conversation, AiMessageSenderType.AI, "m10", NOW.minusSeconds(10)),
                message(39L, conversation, AiMessageSenderType.USER, "m9", NOW.minusSeconds(11)),
                message(38L, conversation, AiMessageSenderType.AI, "m8", NOW.minusSeconds(12)));
        when(conversationRepository.findByIdAndUserAndStatus(20L, student, AiConversationStatus.ACTIVE))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(newestFirst);
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(invocation -> assignId(invocation.getArgument(0), 50L));
        when(conversationRepository.save(any(AiConversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiTutorClient.generateReply(any())).thenReturn(new AiTutorResponse("Reply", null));

        service().sendMessage(student, 20L, new SendAiTutorMessageRequest("latest"));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findByConversationOrderByCreatedAtDesc(eq(conversation), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        ArgumentCaptor<AiTutorRequest> requestCaptor = ArgumentCaptor.forClass(AiTutorRequest.class);
        verify(aiTutorClient).generateReply(requestCaptor.capture());
        assertThat(requestCaptor.getValue().recentMessages())
                .extracting(AiTutorRequest.MessagePayload::message)
                .containsExactly("m8", "m9", "m10");
    }

    @Test
    void listMapsCleanupWarningWhenWarnTimeHasPassed() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        AiConversation conversation = conversation(20L, student, NOW.minusSeconds(60));
        conversation.setCleanupWarnAt(NOW.minusSeconds(1));
        when(conversationRepository.findByUserAndStatusOrderByLastMessageAtDesc(
                student, AiConversationStatus.ACTIVE, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(conversation), PageRequest.of(0, 10), 1));

        AiConversationSummaryResponse response = service().list(student, PageRequest.of(0, 10)).getContent().getFirst();

        assertThat(response.cleanupWarning()).isTrue();
    }

    @Test
    void archiveSetsArchivedStatusAndDeleteAfter() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        AiConversation conversation = conversation(20L, student, NOW.minusSeconds(60));
        when(conversationRepository.findByIdAndUserAndStatus(20L, student, AiConversationStatus.ACTIVE))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any(AiConversation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiConversationSummaryResponse response = service().archive(student, 20L);

        assertThat(response.status()).isEqualTo(AiConversationStatus.ARCHIVED);
        assertThat(conversation.getArchivedAt()).isEqualTo(NOW);
        assertThat(conversation.getDeleteAfter()).isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
    }

    @Test
    void restoreArchivedClearsDeleteAfterAndRefreshesRetention() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        AiConversation conversation = conversation(20L, student, NOW.minusSeconds(60));
        conversation.setStatus(AiConversationStatus.ARCHIVED);
        conversation.setArchivedAt(NOW.minusSeconds(30));
        conversation.setDeleteAfter(NOW.plusSeconds(30));
        when(conversationRepository.findById(20L)).thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any(AiConversation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiConversationSummaryResponse response = service().restoreArchived(20L);

        assertThat(response.status()).isEqualTo(AiConversationStatus.ACTIVE);
        assertThat(conversation.getArchivedAt()).isNull();
        assertThat(conversation.getDeleteAfter()).isNull();
        assertThat(conversation.getLastMessageAt()).isEqualTo(NOW);
        assertThat(conversation.getCleanupWarnAt()).isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
        assertThat(conversation.getArchiveAfter()).isEqualTo(NOW.plusSeconds(32L * 24 * 60 * 60));
    }

    @Test
    void restoreArchivedRejectsActiveConversation() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        AiConversation conversation = conversation(20L, student, NOW.minusSeconds(60));
        when(conversationRepository.findById(20L)).thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> service().restoreArchived(20L))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void studentOnlyMethodsRejectTeacher() throws Exception {
        User teacher = user(2L, UserRole.TEACHER);

        assertThatThrownBy(() -> service().list(teacher, PageRequest.of(0, 10)))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service().get(teacher, 20L))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service().sendMessage(teacher, 20L, new SendAiTutorMessageRequest("Hello")))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service().archive(teacher, 20L))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private AiTutorService service() {
        return new AiTutorService(conversationRepository, messageRepository, feedbackRepository, aiTutorClient, CLOCK);
    }

    private User user(Long id, UserRole role) throws Exception {
        User user = new User();
        setField(user, "id", id);
        user.setEmail("user" + id + "@example.com");
        user.setFullName("User " + id);
        user.setPasswordHash("hash");
        user.setRole(role);
        return user;
    }

    private AiConversation conversation(Long id, User user, Instant lastMessageAt) throws Exception {
        AiConversation conversation = new AiConversation();
        assignId(conversation, id);
        conversation.setUser(user);
        conversation.setTitle("Speaking practice");
        conversation.setPracticeType(AiTutorPracticeType.FREE_CHAT);
        conversation.setStatus(AiConversationStatus.ACTIVE);
        conversation.setStartedAt(NOW.minusSeconds(3600));
        conversation.setLastMessageAt(lastMessageAt);
        conversation.setCleanupWarnAt(lastMessageAt.plusSeconds(30L * 24 * 60 * 60));
        conversation.setArchiveAfter(lastMessageAt.plusSeconds(32L * 24 * 60 * 60));
        return conversation;
    }

    private AiMessage message(Long id, AiConversation conversation, AiMessageSenderType senderType, String text, Instant createdAt)
            throws Exception {
        AiMessage message = new AiMessage();
        assignId(message, id);
        message.setSenderType(senderType);
        message.setMessage(text);
        setField(message, "createdAt", createdAt);
        conversation.addMessage(message);
        return message;
    }

    private AiFeedback feedback(Long id, AiMessage message) throws Exception {
        AiFeedback feedback = new AiFeedback();
        assignId(feedback, id);
        feedback.setErrorType(AiTutorFeedbackType.GRAMMAR);
        feedback.setOriginalText("I go school");
        feedback.setCorrectedText("I go to school");
        feedback.setExplanation("Add the preposition.");
        feedback.setMessage(message);
        return feedback;
    }

    private <T> T assignId(T target, Long id) throws Exception {
        setField(target, "id", id);
        return target;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException exception) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
