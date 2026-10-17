package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.audio.service.LessonAudioFile;
import com.learne.backend.teacher.content.audio.service.LessonAudioFileService;
import com.learne.backend.teacher.content.importexport.security.ClamAvClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class LessonAudioFileServiceTests {
    private static final int LIMIT = 20 * 1024 * 1024;
    // Minimal signature fixtures exercise the existing header policy, not decoder validity.
    private static final byte[] MP3 = {'I', 'D', '3', 1, 2, 3};
    private final ClamAvClient scanner = mock(ClamAvClient.class);
    private final LessonAudioFileService service = new LessonAudioFileService(scanner);

    @TempDir
    Path tempDir;

    @Test
    void rejectsRenamedTextBeforeScanning() {
        assertRejected(file("audio/mpeg", "not really mp3".getBytes(StandardCharsets.US_ASCII)),
                HttpStatus.BAD_REQUEST);
        verifyNoInteractions(scanner);
    }

    @Test
    void rejectsNullBeforeScanning() {
        assertRejected(null, HttpStatus.BAD_REQUEST);
        verifyNoInteractions(scanner);
    }

    @Test
    void rejectsDeclaredEmptyBeforeReading() throws Exception {
        MultipartFile input = mock(MultipartFile.class);
        when(input.isEmpty()).thenReturn(true);

        assertRejected(input, HttpStatus.BAD_REQUEST);

        verify(input, never()).getInputStream();
        verifyNoInteractions(scanner);
    }

    @Test
    void rejectsDeclaredOversizeBeforeReading() throws Exception {
        MultipartFile input = mock(MultipartFile.class);
        when(input.getSize()).thenReturn((long) LIMIT + 1);

        assertRejected(input, HttpStatus.PAYLOAD_TOO_LARGE);

        verify(input, never()).getInputStream();
        verify(input, never()).getBytes();
        verifyNoInteractions(scanner);
    }

    @Test
    void acceptsExactlyTwentyMiB() {
        byte[] bytes = Arrays.copyOf(MP3, LIMIT);

        LessonAudioFile result = service.validateAndScan(file("audio/mpeg", bytes));

        assertThat(result.getSize()).isEqualTo(LIMIT);
        assertThat(result.getBytes()).isEqualTo(bytes);
        verify(scanner).assertClean(eq("lesson-audio"), eq(bytes));
    }

    @Test
    void rejectsActualLimitPlusOneWhenDeclaredSizeLiesAndBoundsTheRead() throws Exception {
        AtomicInteger bytesRead = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                bytesRead.incrementAndGet();
                return 'I';
            }

            @Override
            public int read(byte[] bytes, int offset, int length) {
                Arrays.fill(bytes, offset, offset + length, (byte) 'I');
                bytesRead.addAndGet(length);
                return length;
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        MultipartFile input = streamingInput(endless, 1);

        assertRejected(input, HttpStatus.PAYLOAD_TOO_LARGE);

        assertThat(bytesRead.get()).isEqualTo(LIMIT + 1);
        assertThat(closes.get()).isEqualTo(1);
        verify(input, never()).getBytes();
        verifyNoInteractions(scanner);
    }

    @Test
    void rejectsActualEmptyEvenWhenMetadataClaimsNonempty() throws Exception {
        assertRejected(streamingInput(new ByteArrayInputStream(new byte[0]), 1), HttpStatus.BAD_REQUEST);
        verifyNoInteractions(scanner);
    }

    @Test
    void mapsStreamOpenIOExceptionToBadRequest() throws Exception {
        MultipartFile input = mock(MultipartFile.class);
        when(input.getInputStream()).thenThrow(new IOException("cannot open"));

        assertRejected(input, HttpStatus.BAD_REQUEST);
        verifyNoInteractions(scanner);
    }

    @Test
    void mapsStreamReadIOExceptionToBadRequestAndClosesStream() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("cannot read");
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };

        assertRejected(streamingInput(broken, 1), HttpStatus.BAD_REQUEST);

        assertThat(closes.get()).isEqualTo(1);
        verifyNoInteractions(scanner);
    }

    @ParameterizedTest
    @MethodSource("allowedHeaders")
    void acceptsOnlyExistingMimeAndSignaturePairs(String contentType, byte[] bytes) {
        LessonAudioFile result = service.validateAndScan(file(contentType, bytes));

        assertThat(result.getContentType()).isEqualTo(contentType);
        assertThat(result.getBytes()).isEqualTo(bytes);
        verify(scanner).assertClean(eq("lesson-audio"), eq(bytes));
    }

    static Stream<Arguments> allowedHeaders() {
        byte[] wav = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};
        return Stream.of(
                Arguments.of("audio/mpeg", MP3),
                Arguments.of("audio/mpeg", new byte[] {(byte) 0xff, (byte) 0xfb}),
                Arguments.of("audio/wav", wav),
                Arguments.of("audio/x-wav", wav),
                Arguments.of("audio/mp4", new byte[] {0, 0, 0, 16, 'f', 't', 'y', 'p'}),
                Arguments.of("audio/ogg", new byte[] {'O', 'g', 'g', 'S'}));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"application/octet-stream", "audio/aac", "audio/flac", "audio/webm",
            "audio/mp3", "video/mp4", "Audio/MPEG", "audio/mpeg; charset=UTF-8"})
    void rejectsMimeOutsideExistingAllowlist(String contentType) {
        assertRejected(file(contentType, MP3), HttpStatus.BAD_REQUEST);
        verifyNoInteractions(scanner);
    }

    @ParameterizedTest
    @MethodSource("invalidHeaders")
    void rejectsMismatchedOrTruncatedSignatures(String contentType, byte[] bytes) {
        assertRejected(file(contentType, bytes), HttpStatus.BAD_REQUEST);
        verifyNoInteractions(scanner);
    }

    static Stream<Arguments> invalidHeaders() {
        return Stream.of(
                Arguments.of("audio/mpeg", new byte[] {'I', 'D'}),
                Arguments.of("audio/mpeg", new byte[] {(byte) 0xff, 0}),
                Arguments.of("audio/wav", MP3),
                Arguments.of("audio/x-wav", new byte[] {'R', 'I', 'F', 'F'}),
                Arguments.of("audio/wav", "RIFF0000NOPE".getBytes(StandardCharsets.US_ASCII)),
                Arguments.of("audio/mp4", new byte[] {0, 0, 0, 16, 'f', 't', 'y'}),
                Arguments.of("audio/mp4", MP3),
                Arguments.of("audio/ogg", new byte[] {'O', 'g', 'g'}),
                Arguments.of("audio/ogg", MP3));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 502})
    void propagatesScannerRejectionWithoutReturningAFile(int status) {
        ApiException failure = new ApiException(HttpStatus.valueOf(status), "scanner rejection");
        doThrow(failure).when(scanner).assertClean(eq("lesson-audio"), any(byte[].class));

        assertThatThrownBy(() -> service.validateAndScan(file("audio/mpeg", MP3))).isSameAs(failure);
        verify(scanner).assertClean(eq("lesson-audio"), eq(MP3));
    }

    @Test
    void uploadWrapperKeepsScannedBytesWithoutRereadingChangingOriginal() throws Exception {
        MultipartFile input = mock(MultipartFile.class);
        byte[] original = MP3.clone();
        byte[] changed = "changed after scan".getBytes(StandardCharsets.US_ASCII);
        when(input.getSize()).thenReturn((long) MP3.length);
        when(input.getContentType()).thenReturn("audio/mpeg", "text/plain");
        when(input.getOriginalFilename()).thenReturn("../../unsafe.mp3");
        when(input.getInputStream()).thenReturn(new ByteArrayInputStream(original), new ByteArrayInputStream(changed));
        when(input.getBytes()).thenReturn(changed);

        LessonAudioFile result = service.validateAndScan(input);
        Arrays.fill(original, (byte) 0);

        assertThat(result.getBytes()).isEqualTo(MP3);
        try (InputStream stream = result.getInputStream()) {
            assertThat(stream.readAllBytes()).isEqualTo(MP3);
        }
        assertThat(result.getContentType()).isEqualTo("audio/mpeg");
        assertThat(result.getName()).isEqualTo("file");
        assertThat(result.getOriginalFilename()).isEqualTo("lesson-audio");
        verify(scanner).assertClean(eq("lesson-audio"), eq(MP3));
        verify(input).getInputStream();
        verify(input).getContentType();
        verify(input, never()).getBytes();
        verify(input, never()).getOriginalFilename();
    }

    @Test
    void wrapperDefensivelyCopiesConstructorAndReturnedBytes() throws Exception {
        byte[] source = MP3.clone();
        LessonAudioFile result = new LessonAudioFile("audio/mpeg", source);
        assertThat(result.getBytes()).isEqualTo(MP3);
        source[0] = 0;
        result.getBytes()[1] = 0;

        assertThat(result.getBytes()).isEqualTo(MP3);
        assertThat(result.getSize()).isEqualTo(MP3.length);
        assertThat(result.isEmpty()).isFalse();
        try (InputStream first = result.getInputStream(); InputStream second = result.getInputStream()) {
            assertThat(first).isNotSameAs(second);
            assertThat(first.read()).isEqualTo('I');
            assertThat(second.readAllBytes()).isEqualTo(MP3);
        }
    }

    @Test
    void wrapperReportsEmptyFromItsBytes() {
        LessonAudioFile result = new LessonAudioFile("audio/mpeg", new byte[0]);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.getSize()).isZero();
    }

    @Test
    void wrapperTransferToCreatesAndReplacesDestination() throws Exception {
        LessonAudioFile result = new LessonAudioFile("audio/mpeg", MP3);
        Path destination = tempDir.resolve("destination.bin");

        result.transferTo(destination.toFile());
        assertThat(destination).exists();
        assertThat(Files.readAllBytes(destination)).isEqualTo(MP3);
        Files.writeString(destination, "previous longer contents");
        result.transferTo(destination.toFile());
        assertThat(Files.readAllBytes(destination)).isEqualTo(MP3);
        assertThat(result.getBytes()).isEqualTo(MP3);
    }

    private MockMultipartFile file(String contentType, byte[] bytes) {
        return new MockMultipartFile("file", "../../renamed.mp3", contentType, bytes);
    }

    private MultipartFile streamingInput(InputStream stream, long declaredSize) throws IOException {
        MultipartFile input = mock(MultipartFile.class);
        when(input.getSize()).thenReturn(declaredSize);
        when(input.getContentType()).thenReturn("audio/mpeg");
        when(input.getInputStream()).thenReturn(stream);
        return input;
    }

    private void assertRejected(MultipartFile input, HttpStatus status) {
        assertThatThrownBy(() -> service.validateAndScan(input))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(status));
    }
}
