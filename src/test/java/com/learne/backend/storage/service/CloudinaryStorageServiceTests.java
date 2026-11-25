package com.learne.backend.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.config.CloudinaryProperties;
import com.learne.backend.storage.dto.StorageDownloadResult;
import com.learne.backend.teacher.content.importexport.security.AudioFileValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class CloudinaryStorageServiceTests {

    private final CloudinaryStorageService service = new CloudinaryStorageService(new CloudinaryProperties());

    @TempDir
    Path tempDir;

    @Test
    void marksThePropertiesConstructorAsSpringInjectionPoint() {
        Constructor<?> constructor = Arrays.stream(CloudinaryStorageService.class.getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0] == CloudinaryProperties.class)
                .findFirst()
                .orElseThrow();

        assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
    }

    @Test
    void storageDownloadResultDefensivelyCopiesBytes() {
        byte[] bytes = {1, 2, 3};
        StorageDownloadResult result = new StorageDownloadResult("audio/mpeg", bytes);

        bytes[0] = 9;
        result.bytes()[1] = 9;

        assertThat(result.bytes()).containsExactly(1, 2, 3);
    }

    @Test
    void downloadLessonAudioRejectsUnsafeFallbackUrl() {
        assertThatThrownBy(() -> new CloudinaryStorageService(configuredProperties())
                .downloadLessonAudio("audio-id", "http://127.0.0.1/internal/audio.mp3"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(ex.getMessage()).isEqualTo("Không thể tải audio bài học từ Cloudinary.");
        });
    }

    @Test
    void downloadLessonAudioRejectsDifferentCloudinaryTenant() {
        assertThatThrownBy(() -> new CloudinaryStorageService(
                configuredProperties(), url -> { throw new AssertionError("must reject before opening"); })
                .downloadLessonAudio("audio-id", "https://res.cloudinary.com/other/video/upload/lesson.mp3"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(ex.getMessage()).isEqualTo("Không thể tải audio bài học từ Cloudinary.");
                });
    }

    @Test
    void downloadLessonAudioDoesNotFollowRedirectToUnsafeHost() throws Exception {
        URL allowedUrl = new URL("https://res.cloudinary.com/cloud/video/upload/lesson.mp3");

        assertThatThrownBy(() -> new CloudinaryStorageService(
                configuredProperties(), url -> new RedirectingConnection(allowedUrl))
                .downloadLessonAudio("audio-id", allowedUrl.toString()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(ex.getMessage()).isEqualTo("Không thể tải audio bài học từ Cloudinary.");
                });
    }

    @Test
    void downloadLessonAudioRejectsMissingUrl() {
        CloudinaryProperties properties = configuredProperties();
        CloudinaryStorageService configuredService = new CloudinaryStorageService(properties);

        assertThatThrownBy(() -> configuredService.downloadLessonAudio("", ""))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(ex.getMessage()).isEqualTo("Không tìm thấy URL audio bài học.");
                });
    }

    @Test
    void downloadLessonAudioMapsMalformedUrlToBadGateway() {
        CloudinaryProperties properties = configuredProperties();
        CloudinaryStorageService configuredService = new CloudinaryStorageService(properties);

        assertThatThrownBy(() -> configuredService.downloadLessonAudio("audio-id", "not a URL"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(ex.getMessage()).isEqualTo("Không thể tải audio bài học từ Cloudinary.");
                });
    }

    @Test
    void downloadLessonAudioReturnsFallbackBytesAndGuessesContentType() throws Exception {
        Path audio = tempDir.resolve("lesson.mp3");
        Files.write(audio, new byte[] {'I', 'D', '3', 1, 2, 3});

        StorageDownloadResult result = new CloudinaryStorageService(
                configuredProperties(), url -> audio.toUri().toURL().openConnection())
                .downloadLessonAudio("audio-id", "https://res.cloudinary.com/cloud/video/upload/lesson.mp3");

        assertThat(result.contentType()).isEqualTo("audio/mpeg");
        assertThat(result.bytes()).containsExactly('I', 'D', '3', 1, 2, 3);
    }

    @Test
    void downloadLessonAudioAcceptsGeneratedConfiguredCloudinaryUrl() throws Exception {
        Path audio = tempDir.resolve("lesson.mp3");
        Files.write(audio, new byte[] {'I', 'D', '3', 1, 2, 3});

        StorageDownloadResult result = new CloudinaryStorageService(
                configuredProperties(), url -> audio.toUri().toURL().openConnection())
                .downloadLessonAudio("lesson.mp3", "");

        assertThat(result.contentType()).isEqualTo("audio/mpeg");
        assertThat(result.bytes()).containsExactly('I', 'D', '3', 1, 2, 3);
    }

    @Test
    void downloadLessonAudioRejectsOversizedFallback() throws Exception {
        Path audio = tempDir.resolve("lesson.mp3");
        Files.write(audio, new byte[(int) AudioFileValidator.MAX_AUDIO_BYTES + 1]);

        assertThatThrownBy(() -> new CloudinaryStorageService(
                configuredProperties(), url -> audio.toUri().toURL().openConnection())
                .downloadLessonAudio("audio-id", "https://res.cloudinary.com/cloud/video/upload/lesson.mp3"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(ex.getMessage()).isEqualTo("Audio bài học vượt quá dung lượng cho phép.");
                });
    }

    @Test
    void downloadLessonAudioRejectsUnsupportedContentTypeAndExtension() throws Exception {
        Path audio = tempDir.resolve("lesson.txt");
        Files.write(audio, new byte[] {'I', 'D', '3', 1, 2, 3});

        assertThatThrownBy(() -> new CloudinaryStorageService(configuredProperties())
                .downloadLessonAudio("audio-id", audio.toUri().toString()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(ex.getMessage()).isEqualTo("Không thể tải audio bài học từ Cloudinary.");
                });
    }

    @Test
    void downloadLessonAudioRejectsInvalidAudioBytes() throws Exception {
        Path audio = tempDir.resolve("lesson.mp3");
        Files.write(audio, "not audio".getBytes());

        assertThatThrownBy(() -> new CloudinaryStorageService(configuredProperties())
                .downloadLessonAudio("audio-id", audio.toUri().toString()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(ex.getMessage()).isEqualTo("Không thể tải audio bài học từ Cloudinary.");
                });
    }

    private CloudinaryProperties configuredProperties() {
        CloudinaryProperties properties = new CloudinaryProperties();
        properties.setCloudName("cloud");
        properties.setApiKey("key");
        properties.setApiSecret("secret");
        return properties;
    }

    private static class RedirectingConnection extends HttpURLConnection {
        private RedirectingConnection(URL url) {
            super(url);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (getInstanceFollowRedirects()) {
                return new ByteArrayInputStream(new byte[] {'I', 'D', '3', 1, 2, 3});
            }
            throw new IOException("redirect rejected");
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }

    @Test
    void uploadTeacherAvatarRejectsSpoofedPngContent() {
        MockMultipartFile file = new MockMultipartFile(
                "avatar",
                "avatar.png",
                "image/png",
                "not an image".getBytes()
        );

        assertThatThrownBy(() -> service.uploadTeacherAvatar(file))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Ảnh đại diện không hợp lệ.");
                });
    }

    @Test
    void uploadStudentAvatarRejectsSpoofedPngContent() {
        MockMultipartFile file = new MockMultipartFile(
                "avatar",
                "avatar.png",
                "image/png",
                "not an image".getBytes()
        );

        assertThatThrownBy(() -> service.uploadStudentAvatar(file))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Ảnh đại diện không hợp lệ.");
                });
    }

    @Test
    void uploadStudentAvatarRejectsOversizedFile() {
        CloudinaryProperties properties = new CloudinaryProperties();
        properties.setMaxAvatarBytes(4);
        CloudinaryStorageService limitedService = new CloudinaryStorageService(properties);
        MockMultipartFile file = new MockMultipartFile(
                "avatar", "avatar.png", "image/png",
                new byte[] {0x01, 0x02, 0x03, 0x04, 0x05}
        );

        assertThatThrownBy(() -> limitedService.uploadStudentAvatar(file))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("File vượt quá dung lượng cho phép.");
                });
    }

    @Test
    void uploadStudentAvatarRejectsUnsupportedMimeType() {
        MockMultipartFile file = new MockMultipartFile(
                "avatar", "avatar.gif", "image/gif",
                new byte[] {0x47, 0x49, 0x46, 0x38}
        );

        assertThatThrownBy(() -> service.uploadStudentAvatar(file))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Ảnh đại diện không hợp lệ.");
                });
    }

    @Test
    void uploadTeacherIntroductionVideoRejectsSpoofedMp4Content() {
        MockMultipartFile file = new MockMultipartFile(
                "video",
                "intro.mp4",
                "video/mp4",
                "not a video".getBytes()
        );

        assertThatThrownBy(() -> service.uploadTeacherIntroductionVideo(file))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Video giới thiệu không hợp lệ.");
                });
    }

    @Test
    void uploadTeacherAvatarAcceptsKnownImageSignatures() {
        assertAcceptedAvatarSignature("image/png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        assertAcceptedAvatarSignature("image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
        assertAcceptedAvatarSignature("image/webp", new byte[] {0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50});
    }

    @Test
    void uploadTeacherAvatarRejectsGifContentType() {
        MockMultipartFile file = new MockMultipartFile(
                "avatar",
                "avatar.gif",
                "image/gif",
                new byte[] {0x47, 0x49, 0x46, 0x38, 0x39, 0x61}
        );

        assertThatThrownBy(() -> service.uploadTeacherAvatar(file))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Ảnh đại diện không hợp lệ.");
                });
    }

    @Test
    void uploadTeacherIntroductionVideoAcceptsKnownVideoSignatures() {
        assertAcceptedVideoSignature("video/mp4", new byte[] {0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D});
        assertAcceptedVideoSignature("video/webm", new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
    }

    @Test
    void uploadCourseCoverRejectsEmptyAndUnsupportedFiles() {
        MockMultipartFile empty = new MockMultipartFile("file", "cover.png", "image/png", new byte[0]);
        assertThatThrownBy(() -> service.uploadCourseCover(empty))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        MockMultipartFile gif = new MockMultipartFile(
                "file", "cover.gif", "image/gif",
                new byte[] {0x47, 0x49, 0x46, 0x38, 0x39, 0x61});
        assertThatThrownBy(() -> service.uploadCourseCover(gif))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void uploadCourseCoverRejectsMismatchedSignatureAndOversizedFile() {
        MockMultipartFile mismatch = new MockMultipartFile(
                "file", "cover.png", "image/png", "not a png".getBytes());
        assertThatThrownBy(() -> service.uploadCourseCover(mismatch))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        MockMultipartFile truncatedPng = new MockMultipartFile(
                "file", "cover.png", "image/png",
                new byte[] {(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,1,2,3});
        assertThatThrownBy(() -> service.uploadCourseCover(truncatedPng))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        byte[] truncatedWebp = new byte[18];
        System.arraycopy(new byte[] {0x52,0x49,0x46,0x46},0,truncatedWebp,0,4);
        System.arraycopy(new byte[] {0x57,0x45,0x42,0x50},0,truncatedWebp,8,4);
        truncatedWebp[4] = 20;
        assertThatThrownBy(() -> service.uploadCourseCover(
                new MockMultipartFile("file","cover.webp","image/webp",truncatedWebp)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        MockMultipartFile oversized = new MockMultipartFile(
                "file", "cover.jpg", "image/jpeg",
                new byte[(int) (5 * 1024 * 1024 + 1)]);
        assertThatThrownBy(() -> service.uploadCourseCover(oversized))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void uploadCourseCoverRejectsOversizedDimensionsAndPixelCount() throws Exception {
        byte[] tooWide = declaredPng(4097, 1);
        assertThatThrownBy(() -> service.uploadCourseCover(
                new MockMultipartFile("file", "cover.png", "image/png", tooWide)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).contains("vượt quá kích thước");
                });

        byte[] tooManyPixels = declaredPng(4096, 4096);
        assertThatThrownBy(() -> service.uploadCourseCover(
                new MockMultipartFile("file", "cover.png", "image/png", tooManyPixels)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).contains("vượt quá kích thước");
                });
    }

    @Test
    void rejectsDeclaredOversizedDimensionsBeforeDecodingRasterForEverySupportedFormat() throws Exception {
        assertRejectedDimensions("image/png", "cover.png", declaredPng(5000, 1));
        assertRejectedDimensions("image/jpeg", "cover.jpg", declaredJpeg(5000, 1));
        assertRejectedDimensions("image/webp", "cover.webp", declaredVp8xWebp(5000, 1));
    }

    @Test
    void uploadCourseCoverAcceptsPngAndJpegBeforeCloudinaryConfigurationCheck() throws Exception {
        MockMultipartFile png = new MockMultipartFile(
                "file", "cover.png", "image/png", encodedPng(100, 100));
        assertThatThrownBy(() -> service.uploadCourseCover(png))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        MockMultipartFile jpeg = new MockMultipartFile(
                "file", "cover.jpg", "image/jpeg", encodedJpeg(100, 100));
        assertThatThrownBy(() -> service.uploadCourseCover(jpeg))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UklGRkwAAABXRUJQVlA4IEAAAABwAwCdASogABgAPm00lkekIyIhKAgAgA2JZQDKLoB+AABBlBAA/vCbQ//ILlhdcjX/8gP+QH/ID/+PfYwoMAAA",
            "UklGRhwAAABXRUJQVlA4TA8AAAAvH8AFAAcQ/Y/+ByKi/wEA",
            "UklGRjoAAABXRUJQVlA4WAoAAAAIAAAAHwAAFwAAVlA4TA8AAAAvH8AFAAcQ/Y/+ByKi/wEARVhJRgQAAAB0ZXN0"
    })
    void uploadCourseCoverAcceptsRealVp8Vp8lAndVp8xWebp(String base64) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(base64);
        BufferedImage decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(decoded).as("WebP fixture must be decoded by ImageIO").isNotNull();
        String decodedFormat = Arrays.stream(ImageIO.getReaderFormatNames())
                .filter(format -> format.toLowerCase().contains("web"))
                .findFirst()
                .orElse("none");
        assertThat(decodedFormat).isNotEqualTo("none");
        MockMultipartFile webp = new MockMultipartFile(
                "file", "cover.webp", "image/webp", bytes);
        assertThatThrownBy(() -> service.uploadCourseCover(webp))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).as(ex.getMessage()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Test
    void uploadCourseCoverRejectsTruncatedVp8lAndCorruptVp8xPayload() {
        byte[] vp8lBytes = Base64.getDecoder().decode(
                "UklGRhwAAABXRUJQVlA4TA8AAAAvH8AFAAcQ/Y/+ByKi/wEA");
        byte[] truncatedVp8l = Arrays.copyOf(vp8lBytes, vp8lBytes.length - 2);
        assertThatThrownBy(() -> service.uploadCourseCover(
                new MockMultipartFile("file", "cover.webp", "image/webp", truncatedVp8l)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        byte[] vp8x = Base64.getDecoder().decode(
                "UklGRjoAAABXRUJQVlA4WAoAAAAIAAAAHwAAFwAAVlA4TA8AAAAvH8AFAAcQ/Y/+ByKi/wEARVhJRgQAAAB0ZXN0");
        vp8x[16] = 100;
        assertThatThrownBy(() -> service.uploadCourseCover(
                new MockMultipartFile("file", "cover.webp", "image/webp", vp8x)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UklGRkwAAABXRUJQVlA4IEAAAABwAwCdASogABgAPm00lkekIyIhKAgAgA2JZQDKLoB+AABBlBAA/vCbQ//ILlhdcjX/8gP+QH/ID/+PfYwoMAAA",
            "UklGRhwAAABXRUJQVlA4TA8AAAAvH8AFAAcQ/Y/+ByKi/wEA",
            "UklGRjoAAABXRUJQVlA4WAoAAAAIAAAAHwAAFwAAVlA4TA8AAAAvH8AFAAcQ/Y/+ByKi/wEARVhJRgQAAAB0ZXN0"
    })
    void uploadCourseCoverRejectsWebpWithValidChunksButCorruptImagePayload(String base64) {
        byte[] corrupt = truncateWebpBitstream(Base64.getDecoder().decode(base64));

        assertThatThrownBy(() -> service.uploadCourseCover(
                new MockMultipartFile("file", "cover.webp", "image/webp", corrupt)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private byte[] encodedPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private byte[] declaredPng(int width, int height) throws IOException {
        byte[] bytes = encodedPng(1, 1);
        java.nio.ByteBuffer.wrap(bytes).putInt(16, width).putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, 17);
        java.nio.ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
        return bytes;
    }

    private byte[] declaredJpeg(int width, int height) throws IOException {
        byte[] bytes = encodedJpeg(1, 1);
        for (int offset = 2; offset + 8 < bytes.length;) {
            if ((bytes[offset] & 0xFF) != 0xFF) {
                offset++;
                continue;
            }
            int marker = bytes[offset + 1] & 0xFF;
            offset += 2;
            if (marker == 0xD8 || marker == 0xD9 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            int segmentLength = ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
            if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2 || marker == 0xC3) {
                bytes[offset + 3] = (byte) (height >>> 8);
                bytes[offset + 4] = (byte) height;
                bytes[offset + 5] = (byte) (width >>> 8);
                bytes[offset + 6] = (byte) width;
                return bytes;
            }
            offset += segmentLength;
        }
        throw new AssertionError("JPEG fixture has no supported start-of-frame segment");
    }

    private byte[] declaredVp8xWebp(int width, int height) {
        byte[] bytes = Base64.getDecoder().decode(
                "UklGRjoAAABXRUJQVlA4WAoAAAAIAAAAHwAAFwAAVlA4TA8AAAAvH8AFAAcQ/Y/+ByKi/wEARVhJRgQAAAB0ZXN0");
        writeLittleEndian24(bytes, 24, width - 1);
        writeLittleEndian24(bytes, 27, height - 1);
        return bytes;
    }

    private void assertRejectedDimensions(String contentType, String filename, byte[] bytes) {
        assertThatThrownBy(() -> service.uploadCourseCover(
                new MockMultipartFile("file", filename, contentType, bytes)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).contains("vượt quá kích thước");
                });
    }

    private void writeLittleEndian24(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
    }

    private byte[] truncateWebpBitstream(byte[] bytes) {
        for (int offset = 12; offset + 8 <= bytes.length;) {
            String chunk = new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkSize = (bytes[offset + 4] & 0xFF)
                    | ((bytes[offset + 5] & 0xFF) << 8)
                    | ((bytes[offset + 6] & 0xFF) << 16)
                    | ((bytes[offset + 7] & 0xFF) << 24);
            int payloadStart = offset + 8;
            if (chunkSize < 0 || payloadStart + chunkSize > bytes.length) {
                throw new AssertionError("Invalid WebP test fixture");
            }
            int imageHeaderSize = switch (chunk) {
                case "VP8 " -> 10;
                case "VP8L" -> 5;
                default -> 0;
            };
            if (imageHeaderSize > 0 && chunkSize > imageHeaderSize) {
                int removedBytes = chunkSize - imageHeaderSize;
                int source = payloadStart + chunkSize + (chunkSize & 1);
                int destination = payloadStart + imageHeaderSize + (imageHeaderSize & 1);
                System.arraycopy(bytes, source, bytes, destination, bytes.length - source);
                bytes = Arrays.copyOf(bytes, bytes.length - removedBytes);
                bytes[offset + 4] = (byte) imageHeaderSize;
                bytes[offset + 5] = (byte) (imageHeaderSize >>> 8);
                bytes[offset + 6] = (byte) (imageHeaderSize >>> 16);
                bytes[offset + 7] = (byte) (imageHeaderSize >>> 24);
                int newChunkSize = bytes.length - 8;
                bytes[4] = (byte) newChunkSize;
                bytes[5] = (byte) (newChunkSize >>> 8);
                bytes[6] = (byte) (newChunkSize >>> 16);
                bytes[7] = (byte) (newChunkSize >>> 24);
                return bytes;
            }
            offset = payloadStart + chunkSize + (chunkSize & 1);
        }
        throw new AssertionError("WebP fixture has no image payload");
    }

    private byte[] encodedJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return output.toByteArray();
    }

    private void assertAcceptedAvatarSignature(String contentType, byte[] bytes) {
        MockMultipartFile file = new MockMultipartFile("avatar", "avatar", contentType, bytes);

        assertThatThrownBy(() -> service.uploadTeacherAvatar(file))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(ex.getMessage()).isEqualTo("Cloudinary chưa được cấu hình.");
                });
    }

    private void assertAcceptedVideoSignature(String contentType, byte[] bytes) {
        MockMultipartFile file = new MockMultipartFile("video", "video", contentType, bytes);

        assertThatThrownBy(() -> service.uploadTeacherIntroductionVideo(file))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(ex.getMessage()).isEqualTo("Cloudinary chưa được cấu hình.");
                });
    }
}
