package com.learne.backend.ai.grammar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learne.backend.ai.grammar.ai.AiGrammarCheckClient;
import com.learne.backend.ai.grammar.ai.AiGrammarCheckResponse;
import com.learne.backend.ai.grammar.dto.GrammarCheckContextRequest;
import com.learne.backend.ai.grammar.dto.GrammarCheckResponse;
import com.learne.backend.ai.grammar.dto.SubmitGrammarCheckRequest;
import com.learne.backend.ai.grammar.entity.GrammarCheck;
import com.learne.backend.ai.grammar.entity.GrammarCheckError;
import com.learne.backend.ai.grammar.enums.GrammarCheckErrorType;
import com.learne.backend.ai.grammar.enums.GrammarCheckStatus;
import com.learne.backend.ai.grammar.repository.GrammarCheckErrorRepository;
import com.learne.backend.ai.grammar.repository.GrammarCheckRepository;
import com.learne.backend.ai.grammar.service.GrammarCheckFailedException;
import com.learne.backend.ai.grammar.service.GrammarCheckService;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.monetization.service.AiUsageGuard;
import com.learne.backend.shared.api.ApiException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class GrammarCheckServiceTests {
    @Mock private GrammarCheckRepository grammarCheckRepository;
    @Mock private GrammarCheckErrorRepository grammarCheckErrorRepository;
    @Mock private AiGrammarCheckClient aiGrammarCheckClient;
    @Mock private AiUsageGuard aiUsageGuard;

    @Test
    void submitDoesNotRollbackFailedGrammarCheckException() throws Exception {
        Method submit = GrammarCheckService.class.getMethod("submit", User.class, SubmitGrammarCheckRequest.class);
        Transactional transactional = submit.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(GrammarCheckFailedException.class);
    }

    @Test
    void submitStoresSortedValidErrorsOnly() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        when(grammarCheckRepository.save(any(GrammarCheck.class))).thenAnswer(invocation -> assignId(invocation.getArgument(0), 10L));
        when(aiGrammarCheckClient.checkGrammar(any())).thenReturn(new AiGrammarCheckResponse(List.of(
                error("are", "GRAMMAR", "is", "Subject verb agreement", 8, 11),
                error("helo", "SPELLING", "hello", "Spelling", 0, 4))));

        GrammarCheckResponse response = service().submit(student, new SubmitGrammarCheckRequest(" helo is are ", null));

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo(GrammarCheckStatus.SUCCEEDED);
        assertThat(response.inputText()).isEqualTo("helo is are");
        assertThat(response.errors()).extracting("errorText").containsExactly("helo", "are");
        assertThat(response.errors()).extracting("sequenceOrder").containsExactly(1, 2);

        ArgumentCaptor<GrammarCheck> checkCaptor = ArgumentCaptor.forClass(GrammarCheck.class);
        verify(grammarCheckRepository, times(2)).save(checkCaptor.capture());
        GrammarCheck saved = checkCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(GrammarCheckStatus.SUCCEEDED);
        assertThat(saved.getErrors()).hasSize(2);
        assertThat(saved.getErrors()).extracting("sequenceOrder").containsExactly(1, 2);
    }

    @Test
    void submitSucceedsWhenAiReturnsEmptyErrors() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        when(grammarCheckRepository.save(any(GrammarCheck.class))).thenAnswer(invocation -> assignId(invocation.getArgument(0), 11L));
        when(aiGrammarCheckClient.checkGrammar(any())).thenReturn(new AiGrammarCheckResponse(List.of()));

        GrammarCheckResponse response = service().submit(student, new SubmitGrammarCheckRequest("Correct sentence.", null));

        assertThat(response.status()).isEqualTo(GrammarCheckStatus.SUCCEEDED);
        assertThat(response.errors()).isEmpty();
    }

    @Test
    void submitStoresFailedAndThrowsWhenAiFails() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        when(grammarCheckRepository.save(any(GrammarCheck.class))).thenAnswer(invocation -> assignId(invocation.getArgument(0), 12L));
        when(aiGrammarCheckClient.checkGrammar(any())).thenThrow(new IllegalStateException("AI unavailable"));

        assertThatThrownBy(() -> service().submit(student, new SubmitGrammarCheckRequest("Check me.", null)))
                .isInstanceOf(GrammarCheckFailedException.class)
                .satisfies(error -> {
                    GrammarCheckFailedException exception = (GrammarCheckFailedException) error;
                    assertThat(exception.getCheckId()).isEqualTo(12L);
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(exception.getMessage()).isEqualTo("AI unavailable");
                });
    }

    @Test
    void submitStoresFailedAndThrowsWhenAiResponseIsNull() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        when(grammarCheckRepository.save(any(GrammarCheck.class))).thenAnswer(invocation -> assignId(invocation.getArgument(0), 16L));
        when(aiGrammarCheckClient.checkGrammar(any())).thenReturn(null);

        assertAiContractFailure(student, 16L);
    }

    @Test
    void submitStoresFailedAndThrowsWhenAiErrorsAreNull() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        when(grammarCheckRepository.save(any(GrammarCheck.class))).thenAnswer(invocation -> assignId(invocation.getArgument(0), 17L));
        when(aiGrammarCheckClient.checkGrammar(any())).thenReturn(new AiGrammarCheckResponse(null));

        assertAiContractFailure(student, 17L);
    }

    @Test
    void submitRejectsTeacherContext() throws Exception {
        User teacher = user(2L, UserRole.TEACHER);
        SubmitGrammarCheckRequest request = new SubmitGrammarCheckRequest(
                "Check me.", new GrammarCheckContextRequest(1L, null, null, null));

        assertThatThrownBy(() -> service().submit(teacher, request))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(grammarCheckRepository, never()).save(any());
    }

    @Test
    void submitRejectsAdmin() throws Exception {
        User admin = user(3L, UserRole.ADMIN);

        assertThatThrownBy(() -> service().submit(admin, new SubmitGrammarCheckRequest("Check me.", null)))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(grammarCheckRepository, never()).save(any());
    }

    @Test
    void submitDropsInvalidPayloadItems() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        when(grammarCheckRepository.save(any(GrammarCheck.class))).thenAnswer(invocation -> assignId(invocation.getArgument(0), 13L));
        when(aiGrammarCheckClient.checkGrammar(any())).thenReturn(new AiGrammarCheckResponse(List.of(
                error("bad", "GRAMMAR", "good", "Valid", 0, 3),
                error("bad", "UNKNOWN", "good", "Invalid enum", 0, 3),
                error("", "GRAMMAR", "good", "Blank text", 0, 3),
                error("bad", "GRAMMAR", "", "Blank suggestion", 0, 3),
                error("bad", "GRAMMAR", "good", "", 0, 3),
                error("bad", "GRAMMAR", "good", "Null start", null, 3),
                error("bad", "GRAMMAR", "good", "Negative start", -1, 3),
                error("bad", "GRAMMAR", "good", "End before start", 3, 3),
                error("bad", "GRAMMAR", "good", "End too high", 0, 99),
                error("mismatch", "GRAMMAR", "good", "Mismatch", 0, 3))));

        GrammarCheckResponse response = service().submit(student, new SubmitGrammarCheckRequest("bad text", null));

        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().errorText()).isEqualTo("bad");
    }

    @Test
    void submitDropsAiErrorsWithOversizedPersistedFields() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        String oversizedText = "a".repeat(501);
        String inputText = oversizedText + " ok bad";
        when(grammarCheckRepository.save(any(GrammarCheck.class))).thenAnswer(invocation -> assignId(invocation.getArgument(0), 15L));
        when(aiGrammarCheckClient.checkGrammar(any())).thenReturn(new AiGrammarCheckResponse(List.of(
                error(oversizedText, "GRAMMAR", "b", "Oversized error text", 0, 501),
                error("ok", "GRAMMAR", "x".repeat(501), "Oversized suggestion", 502, 504),
                error("bad", "GRAMMAR", "good", "Valid", 505, 508))));

        GrammarCheckResponse response = service().submit(student, new SubmitGrammarCheckRequest(inputText, null));

        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().errorText()).isEqualTo("bad");
    }

    @Test
    void submitDropsAiErrorsWithOversizedExplanation() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        when(grammarCheckRepository.save(any(GrammarCheck.class))).thenAnswer(invocation -> assignId(invocation.getArgument(0), 18L));
        when(aiGrammarCheckClient.checkGrammar(any())).thenReturn(new AiGrammarCheckResponse(List.of(
                error("bad", "GRAMMAR", "good", "x".repeat(501), 0, 3),
                error("text", "GRAMMAR", "sentence", "Valid", 4, 8))));

        GrammarCheckResponse response = service().submit(student, new SubmitGrammarCheckRequest("bad text", null));

        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().errorText()).isEqualTo("text");
    }

    @Test
    void listUsesCurrentUserPageableAndMapsPreviewCountAndTimestamps() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        Pageable pageable = PageRequest.of(0, 10);
        Instant createdAt = Instant.parse("2026-09-07T10:00:00Z");
        Instant completedAt = Instant.parse("2026-09-07T10:00:04Z");
        GrammarCheck check = check(20L, student, GrammarCheckStatus.FAILED,
                "x".repeat(121), "Failed safely", createdAt, completedAt);
        when(grammarCheckRepository.findByUserOrderByCreatedAtDesc(student, pageable))
                .thenReturn(new PageImpl<>(List.of(check), pageable, 1));
        when(grammarCheckErrorRepository.countByGrammarCheck(check)).thenReturn(3L);

        Page<?> response = service().list(student, pageable);

        verify(grammarCheckRepository).findByUserOrderByCreatedAtDesc(student, pageable);
        verify(grammarCheckErrorRepository).countByGrammarCheck(check);
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getContent().getFirst())
                .hasFieldOrPropertyWithValue("id", 20L)
                .hasFieldOrPropertyWithValue("status", GrammarCheckStatus.FAILED)
                .hasFieldOrPropertyWithValue("errorCount", 3L)
                .hasFieldOrPropertyWithValue("failureMessage", "Failed safely")
                .hasFieldOrPropertyWithValue("createdAt", createdAt)
                .hasFieldOrPropertyWithValue("completedAt", completedAt);
        assertThat(response.getContent().getFirst()).extracting("inputPreview")
                .asString()
                .hasSize(120);
    }

    @Test
    void getUsesOwnerLookupAndOrderedErrorRepository() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        GrammarCheck check = check(21L, student, GrammarCheckStatus.SUCCEEDED, "bad text", null, null, null);
        GrammarCheckError error = grammarError(31L, check, 1, "bad", GrammarCheckErrorType.GRAMMAR, 0, 3);
        when(grammarCheckRepository.findByIdAndUser(21L, student)).thenReturn(Optional.of(check));
        when(grammarCheckErrorRepository.findByGrammarCheckOrderBySequenceOrderAsc(check)).thenReturn(List.of(error));

        GrammarCheckResponse response = service().get(student, 21L);

        verify(grammarCheckRepository).findByIdAndUser(21L, student);
        verify(grammarCheckErrorRepository).findByGrammarCheckOrderBySequenceOrderAsc(check);
        assertThat(response.id()).isEqualTo(21L);
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().id()).isEqualTo(31L);
        assertThat(response.errors().getFirst().sequenceOrder()).isEqualTo(1);
    }

    @Test
    void getReturnsNotFoundWhenOwnerLookupIsAbsent() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        when(grammarCheckRepository.findByIdAndUser(99L, student)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(student, 99L))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(grammarCheckRepository).findByIdAndUser(99L, student);
        verify(grammarCheckErrorRepository, never()).findByGrammarCheckOrderBySequenceOrderAsc(any());
    }

    @Test
    void submitRejectsBlankNullAndTooLongInputBeforeSavingOrCallingAi() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        List<SubmitGrammarCheckRequest> requests = List.of(
                new SubmitGrammarCheckRequest(" ", null),
                new SubmitGrammarCheckRequest("x".repeat(2001), null));

        for (SubmitGrammarCheckRequest request : requests) {
            assertThatThrownBy(() -> service().submit(student, request))
                    .isInstanceOf(ApiException.class)
                    .extracting("status")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThatThrownBy(() -> service().submit(student, new SubmitGrammarCheckRequest(null, null)))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(grammarCheckRepository, never()).save(any());
        verify(aiGrammarCheckClient, never()).checkGrammar(any());
    }

    @Test
    void submitLimitsAiErrorsToTwentyAndSequencesPersistedErrors() throws Exception {
        User student = user(1L, UserRole.STUDENT);
        String inputText = "a".repeat(25);
        List<AiGrammarCheckResponse.ErrorPayload> aiErrors = IntStream.range(0, 25)
                .mapToObj(index -> error("a", "GRAMMAR", "b", "Replace", index, index + 1))
                .toList();
        when(grammarCheckRepository.save(any(GrammarCheck.class))).thenAnswer(invocation -> assignId(invocation.getArgument(0), 14L));
        when(aiGrammarCheckClient.checkGrammar(any())).thenReturn(new AiGrammarCheckResponse(aiErrors));

        GrammarCheckResponse response = service().submit(student, new SubmitGrammarCheckRequest(inputText, null));

        assertThat(response.errors()).hasSize(20);
        assertThat(response.errors()).extracting("sequenceOrder")
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 20).boxed().toList());
        ArgumentCaptor<GrammarCheck> checkCaptor = ArgumentCaptor.forClass(GrammarCheck.class);
        verify(grammarCheckRepository, times(2)).save(checkCaptor.capture());
        assertThat(checkCaptor.getValue().getErrors()).hasSize(20);
        assertThat(checkCaptor.getValue().getErrors()).extracting("sequenceOrder")
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 20).boxed().toList());
    }

    private GrammarCheckService service() {
        return new GrammarCheckService(grammarCheckRepository, grammarCheckErrorRepository, aiGrammarCheckClient, aiUsageGuard);
    }

    private void assertAiContractFailure(User student, Long checkId) {
        assertThatThrownBy(() -> service().submit(student, new SubmitGrammarCheckRequest("Check me.", null)))
                .isInstanceOf(GrammarCheckFailedException.class)
                .satisfies(error -> {
                    GrammarCheckFailedException exception = (GrammarCheckFailedException) error;
                    assertThat(exception.getCheckId()).isEqualTo(checkId);
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(exception.getMessage()).isEqualTo("AI returned invalid response shape");
                });

        ArgumentCaptor<GrammarCheck> checkCaptor = ArgumentCaptor.forClass(GrammarCheck.class);
        verify(grammarCheckRepository, times(2)).save(checkCaptor.capture());
        GrammarCheck saved = checkCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(GrammarCheckStatus.FAILED);
        assertThat(saved.getFailureMessage()).isEqualTo("AI returned invalid response shape");
    }

    private AiGrammarCheckResponse.ErrorPayload error(
            String errorText, String errorType, String suggestion, String explanation, Integer startPos, Integer endPos) {
        return new AiGrammarCheckResponse.ErrorPayload(errorText, errorType, suggestion, explanation, startPos, endPos);
    }

    private GrammarCheck assignId(GrammarCheck check, Long id) throws Exception {
        setField(check, "id", id);
        return check;
    }

    private User user(Long id, UserRole role) throws Exception {
        User user = new User();
        setField(user, "id", id);
        user.setEmail("user" + id + "@example.com");
        user.setFullName("User " + id);
        user.setRole(role);
        return user;
    }

    private GrammarCheck check(
            Long id,
            User user,
            GrammarCheckStatus status,
            String inputText,
            String failureMessage,
            Instant createdAt,
            Instant completedAt) throws Exception {
        GrammarCheck check = new GrammarCheck();
        setField(check, "id", id);
        check.setUser(user);
        check.setStatus(status);
        check.setInputText(inputText);
        check.setFailureMessage(failureMessage);
        setField(check, "createdAt", createdAt);
        check.setCompletedAt(completedAt);
        return check;
    }

    private GrammarCheckError grammarError(
            Long id,
            GrammarCheck check,
            int sequenceOrder,
            String errorText,
            GrammarCheckErrorType errorType,
            int startPos,
            int endPos) throws Exception {
        GrammarCheckError error = new GrammarCheckError();
        setField(error, "id", id);
        error.setGrammarCheck(check);
        error.setSequenceOrder(sequenceOrder);
        error.setErrorText(errorText);
        error.setErrorType(errorType);
        error.setSuggestion("suggestion");
        error.setExplanation("explanation");
        error.setStartPos(startPos);
        error.setEndPos(endPos);
        return error;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
