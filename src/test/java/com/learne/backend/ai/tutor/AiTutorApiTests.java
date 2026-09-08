package com.learne.backend.ai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.ai.tutor.ai.AiTutorClient;
import com.learne.backend.ai.tutor.ai.AiTutorRequest;
import com.learne.backend.ai.tutor.ai.AiTutorResponse;
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
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class AiTutorApiTests {
    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private AiConversationRepository conversationRepository;
    @Autowired private AiMessageRepository messageRepository;
    @Autowired private AiFeedbackRepository feedbackRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    @MockitoBean private AiTutorClient aiTutorClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void flywayCreatesAiTutorTables() {
        assertThat(tableExists("AI_CONVERSATIONS")).isTrue();
        assertThat(tableExists("AI_MESSAGES")).isTrue();
        assertThat(tableExists("AI_FEEDBACK")).isTrue();
    }

    @Test
    @Transactional
    void persistsConversationMessageAndFeedbackRelationships() {
        User student = saveUser("ai-tutor-student@example.com", UserRole.STUDENT);
        AiConversation conversation = new AiConversation();
        Instant now = Instant.now();
        conversation.setUser(student);
        conversation.setTitle("Practice chat");
        conversation.setPracticeType(AiTutorPracticeType.FREE_CHAT);
        conversation.setStatus(AiConversationStatus.ACTIVE);
        conversation.setLastMessageAt(now);
        conversation.setCleanupWarnAt(now.plusSeconds(86_400));
        conversation.setArchiveAfter(now.plusSeconds(172_800));

        AiMessage message = new AiMessage();
        message.setSenderType(AiMessageSenderType.USER);
        message.setMessage("She are happy");
        conversation.addMessage(message);

        AiConversation secondConversation = new AiConversation();
        secondConversation.addMessage(message);
        assertThat(conversation.getMessages()).isEmpty();
        assertThat(secondConversation.getMessages()).containsExactly(message);
        conversation.addMessage(message);
        assertThat(secondConversation.getMessages()).isEmpty();

        AiFeedback feedback = new AiFeedback();
        feedback.setErrorType(AiTutorFeedbackType.GRAMMAR);
        feedback.setOriginalText("are");
        feedback.setCorrectedText("is");
        feedback.setExplanation("Use singular verb agreement.");
        message.setFeedback(feedback);

        conversationRepository.save(conversation);
        entityManager.flush();
        entityManager.clear();

        List<AiConversation> conversations = conversationRepository.findAll();
        List<AiMessage> messages = messageRepository.findAll();
        List<AiFeedback> feedbackItems = feedbackRepository.findAll();

        assertThat(conversations).hasSize(1);
        assertThat(messages).hasSize(1);
        assertThat(feedbackItems).hasSize(1);
        AiConversation persistedConversation = conversations.getFirst();
        AiMessage persistedMessage = messages.getFirst();
        AiFeedback persistedFeedback = feedbackItems.getFirst();
        assertThat(persistedConversation.getMessages()).hasSize(1);
        assertThat(persistedConversation.getMessages().getFirst().getId()).isEqualTo(persistedMessage.getId());
        assertThat(persistedMessage.getConversation().getId()).isEqualTo(persistedConversation.getId());
        assertThat(persistedMessage.getFeedback().getId()).isEqualTo(persistedFeedback.getId());
        assertThat(persistedFeedback.getMessage().getId()).isEqualTo(persistedMessage.getId());
        assertThat(persistedFeedback.getMessage().getSenderType()).isEqualTo(AiMessageSenderType.USER);
    }

    @Test
    void studentCreatesConversationForEachPracticeType() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        String token = loginAndGetAccessToken(student.getEmail());

        for (AiTutorPracticeType practiceType : AiTutorPracticeType.values()) {
            mockMvc.perform(post("/api/ai-tutor/conversations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "title": "Practice %s",
                                      "practiceType": "%s"
                                    }
                                    """.formatted(practiceType.name(), practiceType.name())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.title").value("Practice " + practiceType.name()))
                    .andExpect(jsonPath("$.practiceType").value(practiceType.name()))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        assertThat(conversationRepository.findAll()).hasSize(AiTutorPracticeType.values().length);
    }

    @Test
    void createRejectsTeacherAndAdmin() throws Exception {
        User teacher = saveUser("teacher@example.com", UserRole.TEACHER);
        User admin = saveUser("admin@example.com", UserRole.ADMIN);

        mockMvc.perform(post("/api/ai-tutor/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(teacher.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createConversationJson("Teacher chat", AiTutorPracticeType.FREE_CHAT)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/ai-tutor/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(admin.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createConversationJson("Admin chat", AiTutorPracticeType.FREE_CHAT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listReturnsOnlyCurrentStudentsActiveConversations() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        User otherStudent = saveUser("other-student@example.com", UserRole.STUDENT);
        AiConversation visible = saveConversation(student, "Visible chat", AiTutorPracticeType.FREE_CHAT, AiConversationStatus.ACTIVE);
        saveConversation(student, "Archived chat", AiTutorPracticeType.ROLEPLAY, AiConversationStatus.ARCHIVED);
        saveConversation(otherStudent, "Other chat", AiTutorPracticeType.WRITING_PRACTICE, AiConversationStatus.ACTIVE);

        mockMvc.perform(get("/api/ai-tutor/conversations?page=-4&size=200")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(visible.getId()))
                .andExpect(jsonPath("$.content[0].title").value("Visible chat"));
    }

    @Test
    void detailReturnsMessagesAndFeedback() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        AiConversation conversation = saveConversation(student, "Feedback chat", AiTutorPracticeType.FREE_CHAT, AiConversationStatus.ACTIVE);
        AiMessage userMessage = saveMessage(conversation, AiMessageSenderType.USER, "She are happy");
        saveFeedback(userMessage, AiTutorFeedbackType.GRAMMAR, "are", "is", "Use singular verb agreement.");
        saveMessage(conversation, AiMessageSenderType.AI, "She is happy. Nice sentence!");

        mockMvc.perform(get("/api/ai-tutor/conversations/{id}", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversation.getId()))
                .andExpect(jsonPath("$.messages", hasSize(2)))
                .andExpect(jsonPath("$.messages[0].senderType").value("USER"))
                .andExpect(jsonPath("$.messages[0].feedback.errorType").value("GRAMMAR"))
                .andExpect(jsonPath("$.messages[0].feedback.correctedText").value("is"))
                .andExpect(jsonPath("$.messages[1].senderType").value("AI"));
    }

    @Test
    void nonOwnerCannotReadSendOrArchiveConversation() throws Exception {
        User owner = saveUser("owner@example.com", UserRole.STUDENT);
        User otherStudent = saveUser("other-student@example.com", UserRole.STUDENT);
        AiConversation conversation = saveConversation(owner, "Owner chat", AiTutorPracticeType.FREE_CHAT, AiConversationStatus.ACTIVE);
        String otherToken = loginAndGetAccessToken(otherStudent.getEmail());

        mockMvc.perform(get("/api/ai-tutor/conversations/{id}", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/ai-tutor/conversations/{id}/messages", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson("Hello")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/ai-tutor/conversations/{id}/archive", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/ai-tutor/conversations/{id}", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void sendMessageStoresUserMessageAiMessageAndFeedback() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        AiConversation conversation = saveConversation(student, "Send chat", AiTutorPracticeType.FREE_CHAT, AiConversationStatus.ACTIVE);
        mockSuccessfulTutorReply();

        mockMvc.perform(post("/api/ai-tutor/conversations/{id}/messages", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson("She are happy")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userMessage.message").value("She are happy"))
                .andExpect(jsonPath("$.userMessage.feedback.errorType").value("GRAMMAR"))
                .andExpect(jsonPath("$.aiMessage.message").value("She is happy. Great try!"))
                .andExpect(jsonPath("$.conversation.messagePreview").value("She is happy. Great try!"));

        List<AiMessage> messages = messageRepository.findByConversationOrderByCreatedAtAsc(conversationRepository.findById(conversation.getId()).orElseThrow());
        assertThat(messages).extracting(AiMessage::getSenderType).containsExactly(AiMessageSenderType.USER, AiMessageSenderType.AI);
        assertThat(feedbackRepository.findAll()).hasSize(1);
        verify(aiTutorClient).generateReply(any(AiTutorRequest.class));
    }

    @Test
    void aiFailureStoresUserMessageOnlyAndReturnsBadGateway() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        AiConversation conversation = saveConversation(student, "Failure chat", AiTutorPracticeType.FREE_CHAT, AiConversationStatus.ACTIVE);
        when(aiTutorClient.generateReply(any(AiTutorRequest.class))).thenThrow(new RuntimeException("AI down"));

        mockMvc.perform(post("/api/ai-tutor/conversations/{id}/messages", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson("Hello AI")))
                .andExpect(status().isBadGateway());

        List<AiMessage> messages = messageRepository.findByConversationOrderByCreatedAtAsc(conversationRepository.findById(conversation.getId()).orElseThrow());
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getSenderType()).isEqualTo(AiMessageSenderType.USER);
        assertThat(messages.getFirst().getMessage()).isEqualTo("Hello AI");
        assertThat(feedbackRepository.findAll()).isEmpty();
    }

    @Test
    void archivedConversationIsHiddenFromStudentApis() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        AiConversation conversation = saveConversation(student, "Archived chat", AiTutorPracticeType.FREE_CHAT, AiConversationStatus.ARCHIVED);
        String token = loginAndGetAccessToken(student.getEmail());

        mockMvc.perform(get("/api/ai-tutor/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
        mockMvc.perform(get("/api/ai-tutor/conversations/{id}", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/ai-tutor/conversations/{id}/messages", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson("Hello")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/ai-tutor/conversations/{id}/archive", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void studentDeleteArchivesConversation() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        AiConversation conversation = saveConversation(student, "Delete chat", AiTutorPracticeType.FREE_CHAT, AiConversationStatus.ACTIVE);

        mockMvc.perform(delete("/api/ai-tutor/conversations/{id}", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(student.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversation.getId()))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        AiConversation archived = conversationRepository.findById(conversation.getId()).orElseThrow();
        assertThat(archived.getStatus()).isEqualTo(AiConversationStatus.ARCHIVED);
        assertThat(archived.getArchivedAt()).isNotNull();
        assertThat(archived.getDeleteAfter()).isNotNull();
    }

    @Test
    void adminRestoresArchivedConversation() throws Exception {
        User student = saveUser("student@example.com", UserRole.STUDENT);
        User admin = saveUser("admin@example.com", UserRole.ADMIN);
        AiConversation conversation = saveConversation(student, "Archived chat", AiTutorPracticeType.FREE_CHAT, AiConversationStatus.ARCHIVED);

        mockMvc.perform(post("/api/admin/ai-tutor/conversations/{id}/restore", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAndGetAccessToken(admin.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversation.getId()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        AiConversation restored = conversationRepository.findById(conversation.getId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(AiConversationStatus.ACTIVE);
        assertThat(restored.getArchivedAt()).isNull();
        assertThat(restored.getDeleteAfter()).isNull();
    }

    private boolean tableExists(String tableName) {
        Number count = (Number) entityManager.createNativeQuery("""
                select count(*)
                from information_schema.tables
                where upper(table_name) = :tableName
                """)
                .setParameter("tableName", tableName)
                .getSingleResult();
        return count.longValue() > 0;
    }

    private void mockSuccessfulTutorReply() {
        when(aiTutorClient.generateReply(any(AiTutorRequest.class)))
                .thenReturn(new AiTutorResponse(
                        "She is happy. Great try!",
                        new AiTutorResponse.FeedbackPayload("GRAMMAR", "are", "is", "Use singular verb agreement.")));
    }

    private User saveUser(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(role.name() + " User");
        user.setPasswordHash(passwordEncoder.encode("Password123"));
        user.setRole(role);
        user.setActive(true);
        user.setLocked(false);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }

    private AiConversation saveConversation(User user, String title, AiTutorPracticeType practiceType, AiConversationStatus status) {
        Instant now = Instant.now();
        AiConversation conversation = new AiConversation();
        conversation.setUser(user);
        conversation.setTitle(title);
        conversation.setPracticeType(practiceType);
        conversation.setStatus(status);
        conversation.setStartedAt(now.minusSeconds(60));
        conversation.setLastMessageAt(now);
        conversation.setCleanupWarnAt(now.plusSeconds(86_400));
        conversation.setArchiveAfter(now.plusSeconds(172_800));
        if (status == AiConversationStatus.ARCHIVED) {
            conversation.setArchivedAt(now);
            conversation.setDeleteAfter(now.plusSeconds(86_400));
        }
        return conversationRepository.saveAndFlush(conversation);
    }

    private AiMessage saveMessage(AiConversation conversation, AiMessageSenderType senderType, String messageText) {
        AiMessage message = new AiMessage();
        message.setSenderType(senderType);
        message.setMessage(messageText);
        conversation.addMessage(message);
        return messageRepository.saveAndFlush(message);
    }

    private AiFeedback saveFeedback(
            AiMessage message,
            AiTutorFeedbackType errorType,
            String originalText,
            String correctedText,
            String explanation) {
        AiFeedback feedback = new AiFeedback();
        feedback.setErrorType(errorType);
        feedback.setOriginalText(originalText);
        feedback.setCorrectedText(correctedText);
        feedback.setExplanation(explanation);
        message.setFeedback(feedback);
        return feedbackRepository.saveAndFlush(feedback);
    }

    private String loginAndGetAccessToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "Password123"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String createConversationJson(String title, AiTutorPracticeType practiceType) {
        return """
                {
                  "title": "%s",
                  "practiceType": "%s"
                }
                """.formatted(title, practiceType.name());
    }

    private String messageJson(String message) {
        return """
                {
                  "message": "%s"
                }
                """.formatted(message);
    }
}
