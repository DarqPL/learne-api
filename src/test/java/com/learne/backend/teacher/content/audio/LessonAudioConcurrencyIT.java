package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.teacher.content.audio.dto.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LessonAudioConcurrencyIT extends AudioPostgresTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"a", "b"})
    void competingUploadsBothPreflightOnlyOneWinsAndOnlyLoserAssetIsDestroyed(String winner) throws Exception {
        String loser = winner.equals("a") ? "b" : "a";
        CountDownLatch bothStored = new CountDownLatch(2);
        CountDownLatch releaseA = new CountDownLatch(1), releaseB = new CountDownLatch(1);
        when(storage.uploadLessonAudio(any())).thenAnswer(i -> {
            outsideTransaction();
            String name = ((org.springframework.web.multipart.MultipartFile) i.getArgument(0)).getBytes()[5] == 'a' ? "a" : "b";
            bothStored.countDown();
            await(name.equals("a") ? releaseA : releaseB);
            return uploaded(name);
        });
        var a = workers.submit(() -> audio.upload(draft.teacher(), AudioTargetKind.REVISION, draft.revisionLessonId(), file("a")));
        var b = workers.submit(() -> audio.upload(draft.teacher(), AudioTargetKind.REVISION, draft.revisionLessonId(), file("b")));
        try {
            await(bothStored);
            (winner.equals("a") ? releaseA : releaseB).countDown();
            assertThat(get(winner.equals("a") ? a : b).audioPublicId()).isEqualTo(state(winner).publicId());
            (winner.equals("a") ? releaseB : releaseA).countDown();
            conflict(winner.equals("a") ? b : a);
            assertMirrored(state(winner));
            media.assertIntentAndDrain();
            verify(storage).delete(state(loser).publicId(), StorageResourceType.VIDEO);
            verify(storage, never()).delete(eq(state(winner).publicId()), any());
        } finally { releaseA.countDown(); releaseB.countDown(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deleteAndUploadSerializeInBothWinnerOrders(boolean deleteFirst) throws Exception {
        seedAudio("old");
        try (var gate = gateUpload()) {
            var uploading = workers.submit(this::upload);
            gate.entered();
            if (deleteFirst) {
                courseBlocks(() -> audio.delete(draft.teacher(), AudioTargetKind.REVISION, draft.revisionLessonId()));
                gate.close();
                conflict(uploading);
            } else {
                gate.close(); get(uploading);
                courseBlocks(() -> audio.delete(draft.teacher(), AudioTargetKind.REVISION, draft.revisionLessonId()));
            }
            assertMirrored(LessonAudioState.empty());
        }
    }

    @Test
    void audioFinalizationWaitsOnNativeCourseLock() throws Exception {
        courseBlocks(this::upload);
        assertMirrored(state("new"));
    }
}
