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
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
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
