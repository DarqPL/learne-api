package com.learne.backend.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.config.CloudinaryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class CloudinaryStorageServiceTests {

    private final CloudinaryStorageService service = new CloudinaryStorageService(new CloudinaryProperties());

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
