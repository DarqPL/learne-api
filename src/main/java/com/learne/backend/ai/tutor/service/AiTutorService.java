package com.learne.backend.ai.tutor.service;

import com.learne.backend.ai.tutor.ai.AiTutorClient;
import com.learne.backend.ai.tutor.ai.AiTutorRequest;
import com.learne.backend.ai.tutor.ai.AiTutorResponse;
import com.learne.backend.ai.tutor.dto.AiConversationDetailResponse;
import com.learne.backend.ai.tutor.dto.AiConversationSummaryResponse;
import com.learne.backend.ai.tutor.dto.AiFeedbackResponse;
import com.learne.backend.ai.tutor.dto.AiMessageResponse;
import com.learne.backend.ai.tutor.dto.CreateAiConversationRequest;
import com.learne.backend.ai.tutor.dto.SendAiTutorMessageRequest;
import com.learne.backend.ai.tutor.dto.SendAiTutorMessageResponse;
import com.learne.backend.ai.tutor.entity.AiConversation;
import com.learne.backend.ai.tutor.entity.AiFeedback;
import com.learne.backend.ai.tutor.entity.AiMessage;
import com.learne.backend.ai.tutor.enums.AiConversationStatus;
import com.learne.backend.ai.tutor.enums.AiMessageSenderType;
import com.learne.backend.ai.tutor.enums.AiTutorFeedbackType;
import com.learne.backend.ai.tutor.repository.AiConversationRepository;
import com.learne.backend.ai.tutor.repository.AiFeedbackRepository;
import com.learne.backend.ai.tutor.repository.AiMessageRepository;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.monetization.enums.AiUsageFeature;
import com.learne.backend.monetization.service.AiUsageGuard;
import com.learne.backend.shared.api.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTutorService {
    private static final Duration WARNING_AFTER = Duration.ofDays(30);
    private static final Duration ARCHIVE_AFTER = Duration.ofDays(32);
    private static final Duration HARD_DELETE_AFTER_ARCHIVE = Duration.ofDays(30);
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_AI_REPLY_LENGTH = 4000;
    private static final int MAX_FEEDBACK_LENGTH = 1000;
    private static final int RECENT_HISTORY_SIZE = 10;
    private static final int PREVIEW_LENGTH = 120;
    private static final String STUDENT_ONLY_MESSAGE = "AI Tutor is available to students only.";
    private static final String NOT_FOUND_MESSAGE = "AI Tutor conversation not found.";
    private static final String AI_UNAVAILABLE_MESSAGE = "AI Tutor is temporarily unavailable. Please try again later.";

    private final AiConversationRepository conversationRepository;
    private final AiMessageRepository messageRepository;
    private final AiFeedbackRepository feedbackRepository;
    private final AiTutorClient aiTutorClient;
    private final AiUsageGuard aiUsageGuard;
    private final Clock clock;

    public AiTutorService(
            AiConversationRepository conversationRepository,
            AiMessageRepository messageRepository,
            AiFeedbackRepository feedbackRepository,
            AiTutorClient aiTutorClient,
            AiUsageGuard aiUsageGuard,
            Clock clock) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.feedbackRepository = feedbackRepository;
        this.aiTutorClient = aiTutorClient;
        this.aiUsageGuard = aiUsageGuard;
        this.clock = clock;
    }

    @Transactional
    public AiConversationSummaryResponse create(User user, CreateAiConversationRequest request) {
        validateStudent(user);
        String title = validateTitle(request);
        if (request.practiceType() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "practiceType is required");
        }
        Instant now = now();
        AiConversation conversation = new AiConversation();
        conversation.setUser(user);
        conversation.setTitle(title);
        conversation.setPracticeType(request.practiceType());
        conversation.setStatus(AiConversationStatus.ACTIVE);
        conversation.setStartedAt(now);
        refreshRetention(conversation, now);
        return toSummary(conversationRepository.save(conversation));
    }

    @Transactional(readOnly = true)
    public Page<AiConversationSummaryResponse> list(User user, Pageable pageable) {
        validateStudent(user);
        return conversationRepository.findByUserAndStatusOrderByLastMessageAtDesc(
                user, AiConversationStatus.ACTIVE, pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public AiConversationDetailResponse get(User user, Long id) {
        validateStudent(user);
        AiConversation conversation = activeOwnedConversation(user, id);
        return toDetail(conversation, messageRepository.findByConversationOrderByCreatedAtAsc(conversation));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public SendAiTutorMessageResponse sendMessage(User user, Long conversationId, SendAiTutorMessageRequest request) {
        validateStudent(user);
        AiConversation conversation = activeOwnedConversation(user, conversationId);
        String messageText = validateMessage(request);
        List<AiTutorRequest.MessagePayload> recentMessages = recentMessages(conversation);

        AiMessage userMessage = new AiMessage();
        userMessage.setSenderType(AiMessageSenderType.USER);
        userMessage.setMessage(messageText);
        conversation.addMessage(userMessage);
        userMessage = messageRepository.save(userMessage);

        aiUsageGuard.recordAcceptedUsage(user, AiUsageFeature.AI_TUTOR);
        AiTutorResponse aiResponse;
        try {
            aiResponse = aiTutorClient.generateReply(AiTutorRequest.create(
                    conversation.getPracticeType().name(), conversation.getTitle(), messageText, recentMessages));
            String replyText = validateReply(aiResponse);
            AiMessage aiMessage = new AiMessage();
            aiMessage.setSenderType(AiMessageSenderType.AI);
            aiMessage.setMessage(replyText);
            conversation.addMessage(aiMessage);
            aiMessage = messageRepository.save(aiMessage);
            AiFeedback feedback = validFeedback(aiResponse.feedback(), userMessage);
            if (feedback != null) {
                userMessage.setFeedback(feedbackRepository.save(feedback));
            }
            refreshRetention(conversation, now());
            conversationRepository.save(conversation);
            return new SendAiTutorMessageResponse(toMessage(userMessage), toMessage(aiMessage), toSummary(conversation));
        } catch (ApiException exception) {
            refreshRetention(conversation, now());
            conversationRepository.save(conversation);
            throw exception;
        } catch (RuntimeException exception) {
            refreshRetention(conversation, now());
            conversationRepository.save(conversation);
            throw new ApiException(HttpStatus.BAD_GATEWAY, AI_UNAVAILABLE_MESSAGE);
        }
    }

    @Transactional
    public AiConversationSummaryResponse archive(User user, Long id) {
        validateStudent(user);
        AiConversation conversation = activeOwnedConversation(user, id);
        Instant now = now();
        conversation.setStatus(AiConversationStatus.ARCHIVED);
        conversation.setArchivedAt(now);
        conversation.setDeleteAfter(now.plus(HARD_DELETE_AFTER_ARCHIVE));
        return toSummary(conversationRepository.save(conversation));
    }

    @Transactional
    public AiConversationSummaryResponse restoreArchived(Long id) {
        AiConversation conversation = conversationRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));
        if (conversation.getStatus() != AiConversationStatus.ARCHIVED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI Tutor conversation is not archived.");
        }
        conversation.setStatus(AiConversationStatus.ACTIVE);
        conversation.setArchivedAt(null);
        conversation.setDeleteAfter(null);
        refreshRetention(conversation, now());
        return toSummary(conversationRepository.save(conversation));
    }

    private AiConversation activeOwnedConversation(User user, Long id) {
        return conversationRepository.findByIdAndUserAndStatus(id, user, AiConversationStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));
    }

    private void validateStudent(User user) {
        if (user == null || user.getRole() != UserRole.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, STUDENT_ONLY_MESSAGE);
        }
    }

    private String validateTitle(CreateAiConversationRequest request) {
        String title = request == null ? null : request.title();
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "title is required");
        }
        if (trimmed.length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "title must be at most 120 characters");
        }
        return trimmed;
    }

    private String validateMessage(SendAiTutorMessageRequest request) {
        String message = request == null ? null : request.message();
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message is required");
        }
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message must be at most 2000 characters");
        }
        return trimmed;
    }

    private String validateReply(AiTutorResponse response) {
        String reply = response == null ? null : response.replyText();
        String trimmed = reply == null ? "" : reply.trim();
        if (trimmed.isBlank() || trimmed.length() > MAX_AI_REPLY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI Tutor returned invalid response.");
        }
        return trimmed;
    }

    private List<AiTutorRequest.MessagePayload> recentMessages(AiConversation conversation) {
        return messageRepository.findByConversationOrderByCreatedAtDesc(
                        conversation, PageRequest.of(0, RECENT_HISTORY_SIZE))
                .stream()
                .sorted(Comparator.comparing(AiMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(message -> new AiTutorRequest.MessagePayload(message.getSenderType().name(), message.getMessage()))
                .toList();
    }

    private AiFeedback validFeedback(AiTutorResponse.FeedbackPayload payload, AiMessage message) {
        if (payload == null || isBlank(payload.errorType()) || isBlank(payload.originalText())
                || isBlank(payload.correctedText()) || isBlank(payload.explanation())
                || payload.originalText().trim().length() > MAX_FEEDBACK_LENGTH
                || payload.correctedText().trim().length() > MAX_FEEDBACK_LENGTH
                || payload.explanation().trim().length() > MAX_FEEDBACK_LENGTH) {
            return null;
        }
        AiTutorFeedbackType errorType;
        try {
            errorType = AiTutorFeedbackType.valueOf(payload.errorType());
        } catch (IllegalArgumentException exception) {
            return null;
        }

        AiFeedback feedback = new AiFeedback();
        feedback.setMessage(message);
        feedback.setErrorType(errorType);
        feedback.setOriginalText(payload.originalText().trim());
        feedback.setCorrectedText(payload.correctedText().trim());
        feedback.setExplanation(payload.explanation().trim());
        return feedback;
    }

    private void refreshRetention(AiConversation conversation, Instant now) {
        conversation.setLastMessageAt(now);
        conversation.setCleanupWarnAt(now.plus(WARNING_AFTER));
        conversation.setArchiveAfter(now.plus(ARCHIVE_AFTER));
    }

    private AiConversationSummaryResponse toSummary(AiConversation conversation) {
        return new AiConversationSummaryResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getPracticeType(),
                conversation.getStatus(),
                messagePreview(conversation),
                conversation.getLastMessageAt(),
                conversation.getCleanupWarnAt(),
                conversation.getArchiveAfter(),
                cleanupWarning(conversation));
    }

    private AiConversationDetailResponse toDetail(AiConversation conversation, List<AiMessage> messages) {
        return new AiConversationDetailResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getPracticeType(),
                conversation.getStatus(),
                conversation.getStartedAt(),
                conversation.getLastMessageAt(),
                conversation.getCleanupWarnAt(),
                conversation.getArchiveAfter(),
                conversation.getDeleteAfter(),
                cleanupWarning(conversation),
                messages.stream().map(this::toMessage).toList());
    }

    private AiMessageResponse toMessage(AiMessage message) {
        return new AiMessageResponse(
                message.getId(),
                message.getSenderType(),
                message.getMessage(),
                message.getCreatedAt(),
                toFeedback(message.getFeedback()));
    }

    private AiFeedbackResponse toFeedback(AiFeedback feedback) {
        if (feedback == null) {
            return null;
        }
        return new AiFeedbackResponse(
                feedback.getId(),
                feedback.getErrorType().name(),
                feedback.getOriginalText(),
                feedback.getCorrectedText(),
                feedback.getExplanation());
    }

    private String messagePreview(AiConversation conversation) {
        return conversation.getMessages().stream()
                .max(Comparator.comparing(AiMessage::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(AiMessage::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(AiMessage::getMessage)
                .map(message -> message.length() <= PREVIEW_LENGTH ? message : message.substring(0, PREVIEW_LENGTH))
                .orElse(null);
    }

    private Boolean cleanupWarning(AiConversation conversation) {
        return conversation.getCleanupWarnAt() != null && !now().isBefore(conversation.getCleanupWarnAt());
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
