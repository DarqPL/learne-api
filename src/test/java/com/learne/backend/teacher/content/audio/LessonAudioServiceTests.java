package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.content.audio.dto.*;
import com.learne.backend.teacher.content.audio.service.*;
import com.learne.backend.teacher.content.dto.LessonResponse;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.service.CourseWriteOutcome;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

class LessonAudioServiceTests {
    private final LessonAudioReadService reads = mock(LessonAudioReadService.class);
    private final LessonAudioFileService files = mock(LessonAudioFileService.class);
    private final StorageService storage = mock(StorageService.class);
    private final LessonAudioPersistenceService persistence = mock(LessonAudioPersistenceService.class);
    private final LessonAudioCleanupService cleanup = mock(LessonAudioCleanupService.class);
    private final CacheInvalidationService cache = mock(CacheInvalidationService.class);
    private final LessonAudioPostCommitService postCommit = spy(new LessonAudioPostCommitService(cache, cleanup));
    private final LessonAudioService service = new LessonAudioService(reads, files, storage, persistence, cleanup, postCommit);
    private final User teacher = new User();
    private final LessonAudioState uploaded = new LessonAudioState("new-url", "new-id");
    private final LessonAudioToken token = new LessonAudioToken(AudioTargetKind.LIVE, 3L, 1L, 2L,
            LessonAudioState.empty(), 4L, LessonAudioState.empty());
    private final MockMultipartFile input = new MockMultipartFile("file", "audio.mp3", "audio/mpeg", new byte[]{1, 2});
    private final LessonResponse response = LessonResponse.from(new Lesson());

    @BeforeEach
    void setup() {
        when(reads.preflight(teacher, AudioTargetKind.LIVE, 3L)).thenReturn(token);
        when(files.validateAndScan(input)).thenAnswer(invocation -> {
            outsideTransaction();
            return new LessonAudioFile("audio/mpeg", new byte[]{1, 2});
        });
        when(storage.uploadLessonAudio(any())).thenAnswer(invocation -> {
            outsideTransaction();
            assertThat(((org.springframework.web.multipart.MultipartFile) invocation.getArgument(0)).getBytes())
                    .containsExactly(1, 2);
            return new StorageUploadResult(uploaded.url(), uploaded.publicId());
        });
        doAnswer(invocation -> { outsideTransaction(); return invocation.callRealMethod(); })
                .when(postCommit).complete(any(), any(), any());
    }

    @Test
    void committedUploadReturnsDtoDespiteCacheFailureAndOnlyCleansOldAssets() {
        var old = new LessonAudioState("old-url", "old-id");
        when(persistence.replace(eq(teacher), eq(token), eq(uploaded), any()))
                .thenReturn(new LessonAudioMutation(response, 1L, List.of(old)));
        doThrow(new IllegalStateException("cache")).when(cache).evictContentAfterAudioCommit(any());
        assertThat(service.upload(teacher, AudioTargetKind.LIVE, 3L, input)).isSameAs(response);
        verifyNoInteractions(cleanup);
        var order = inOrder(reads, files, storage, persistence, postCommit);
        order.verify(reads).preflight(teacher, AudioTargetKind.LIVE, 3L);
        order.verify(files).validateAndScan(input);
        order.verify(storage).uploadLessonAudio(any());
        order.verify(persistence).replace(eq(teacher), eq(token), eq(uploaded), any());
        order.verify(postCommit).complete(any(), eq(1L), eq(List.of(old)));
    }

    @Test
    void knownRollbackCompensatesAfterCompletionAndCleanupCannotMaskOriginal() {
        RuntimeException original = new IllegalStateException("database");
        when(persistence.replace(eq(teacher), eq(token), eq(uploaded), any())).thenAnswer(invocation -> {
            var transaction = new TransactionTemplate(transactionManager());
            return transaction.execute(status -> {
                invocation.<CourseWriteOutcome>getArgument(3).observe();
                throw original;
            });
        });
        doAnswer(invocation -> { outsideTransaction(); throw new IllegalStateException("cleanup"); })
                .when(cleanup).compensate(any(), eq(1L), eq(List.of(uploaded)), eq(true), any());
        assertThatThrownBy(() -> service.upload(teacher, AudioTargetKind.LIVE, 3L, input)).isSameAs(original);
        verify(cleanup).compensate(any(), eq(1L), eq(List.of(uploaded)), eq(true), any());
        verifyNoInteractions(postCommit);
    }

    @Test
    void failureBeforeProxyHasUnknownOutcomeAndRetainsNewAsset() {
        RuntimeException original = new IllegalStateException("proxy failed");
        when(persistence.replace(eq(teacher), eq(token), eq(uploaded), any())).thenThrow(original);
        assertThatThrownBy(() -> service.upload(teacher, AudioTargetKind.LIVE, 3L, input)).isSameAs(original);
        verifyNoInteractions(cleanup, postCommit);
    }

    @Test
    void committedOutcomeFollowedByProxyFailureRetainsNewAsset() {
        RuntimeException original = new IllegalStateException("after commit");
        when(persistence.replace(eq(teacher), eq(token), eq(uploaded), any())).thenAnswer(invocation -> {
            new TransactionTemplate(transactionManager()).executeWithoutResult(status ->
                    invocation.<CourseWriteOutcome>getArgument(3).observe());
            throw original;
        });
        assertThatThrownBy(() -> service.upload(teacher, AudioTargetKind.LIVE, 3L, input)).isSameAs(original);
        verifyNoInteractions(cleanup, postCommit);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void incompleteProviderResultIs502WithoutBlindCleanup(int variant) {
        StorageUploadResult result = switch (variant) {
            case 0 -> null;
            case 1 -> new StorageUploadResult(null, "id");
            case 2 -> new StorageUploadResult("url", null);
            case 3 -> new StorageUploadResult(" ", "id");
            default -> new StorageUploadResult("url", " ");
        };
        doReturn(result).when(storage).uploadLessonAudio(any());
        assertThatThrownBy(() -> service.upload(teacher, AudioTargetKind.LIVE, 3L, input))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        verifyNoInteractions(persistence, cleanup, postCommit);
    }

    @Test
    void preflightFailureDoesNotScanOrUpload() {
        when(reads.preflight(teacher, AudioTargetKind.LIVE, 3L)).thenThrow(new IllegalStateException("preflight"));
        assertThatThrownBy(() -> service.upload(teacher, AudioTargetKind.LIVE, 3L, input)).hasMessage("preflight");
        verifyNoInteractions(files, storage, persistence, cleanup);
    }

    @Test
    void scannerFailureDoesNotUpload() {
        when(files.validateAndScan(input)).thenThrow(new IllegalStateException("scan"));
        assertThatThrownBy(() -> service.upload(teacher, AudioTargetKind.LIVE, 3L, input)).hasMessage("scan");
        verifyNoInteractions(storage, persistence, cleanup);
    }

    @Test
    void deleteUsesOnlySerializedPersistenceThenPostCommit() {
        when(persistence.remove(teacher, AudioTargetKind.LIVE, 3L))
                .thenReturn(new LessonAudioMutation(response, 1L, List.of()));
        assertThat(service.delete(teacher, AudioTargetKind.LIVE, 3L)).isSameAs(response);
        verify(postCommit).complete(any(), eq(1L), eq(List.of()));
        verifyNoInteractions(reads, files, storage);
    }

    @Test
    void unexpectedPostCommitFailureCannotTriggerUploadCompensation() {
        when(persistence.replace(eq(teacher), eq(token), eq(uploaded), any()))
                .thenReturn(new LessonAudioMutation(response, 1L, List.of()));
        doThrow(new IllegalStateException("unexpected post-commit")).when(postCommit).complete(any(), any(), any());
        assertThatThrownBy(() -> service.upload(teacher, AudioTargetKind.LIVE, 3L, input))
                .hasMessage("unexpected post-commit");
        verifyNoInteractions(cleanup);
    }

    private static void outsideTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private static AbstractPlatformTransactionManager transactionManager() {
        return new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) { }
        };
    }
}
